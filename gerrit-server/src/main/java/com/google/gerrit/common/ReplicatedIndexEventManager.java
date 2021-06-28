
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.common;


import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.PACKFILE_EVENT;

import com.google.common.base.Supplier;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Provider;
import com.google.inject.util.Providers;

import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PackParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is to manage the replication of the change events happening in
 * Gerrit, from within one Gerrit to the other replicated Gerrit.
 * This is meant as a replacement for the Gerrit Plug-In we have been using until
 * Gerrit version 2.10.6, which was catching the change events from the outside and
 * then sending the proposals to GitMS.
 * When an ASYNC (or SYNC) change index event is called by Gerrit it is registered with this class and then
 * it is replicated on the other nodes, where a REPL-SYNC change event is produced.
 * If it's not possible to index the change immediately because the data on the DB is not
 * up-to-date then the change index data is saved in a file for a future retry attempt.
 *
 * A thread will constantly look for files of the retry kind and retry the index of the changes.
 *
 * For some reason there can be some differences on the replicated gerrits if we keep indexing
 * the changes after the original ones on the original node. This can be overcome by indexing again
 * that original change on the original node after a while. This is accomplished using the localReindexQueue
 * which will keep record of the changes indexed on the local node and index them again after a while.
 *
 * See also ChangeIndexer#indexAsync()
 *
 * @author antonio
 */
public class ReplicatedIndexEventManager implements Runnable, Replicator.GerritPublishable {
  public static final String INDEX_EVENTS_REPLICATION_THREAD_NAME = "IndexEventsReplicationThread";
  public static final String NODUPLICATES_REINDEX_THREAD_NAME = "ReIndexEventsNoDuplicatesThread";
  public static final String INCOMING_CHANGES_INDEX_THREAD_NAME = "IncomingChangesChangeIndexerThread";


  private static final Logger log = LoggerFactory.getLogger(ReplicatedIndexEventManager.class);
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();
  private static ReplicatedIndexEventManager instance = null;
  private static Thread indexEventReaderAndPublisherThread = null;

  private boolean finished = false;
  private static Replicator replicatorInstance = null;
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final ChangeIndexer indexer;
  private File indexEventsDirectory = null;
  private final AtomicInteger counter = new AtomicInteger();
  private static final IndexEventsToRetryFileFilter indexEventsToRetryFileFilter = new IndexEventsToRetryFileFilter();
  private final DelayQueue<IndexToReplicateDelayed> localReindexQueue = new DelayQueue<>();

  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 8*1024;
  // Queue of events to replicate (first queue to filter out duplicates using a Set)
  private final LinkedBlockingQueue<IndexToReplicate> unfilteredQueue =   new LinkedBlockingQueue<>(MAX_EVENTS);
  private static UniqueChangesQueue uniqueChangesQueue = null;
  private static Thread changeLimiterThread = null;
  private static IndexIncomingReplicatedEvents indexIncomingChangesEvents;
  private static Thread indexIncomingChangesThread = null;
  private final ConcurrentLinkedQueue<IndexToReplicateComparable> incomingChangeEventsToIndex =   new ConcurrentLinkedQueue<>();
  private final Object indexEventsAreReady = new Object();
  private boolean gerritIndexerRunning = false;
  private Persister<IndexToReplicateComparable> persister;
  private static final String INCOMING_PERSISTED_LINGER_TIME_KEY = "ReplicatedIndexEventManagerIncomingPersistedLingerTime";
  private static final long INCOMING_PERSISTED_LINGER_TIME_DEFAULT = 259200000L; // 3 days

  private static long INCOMING_PERSISTED_LINGER_TIME_VALUE = 0L;

  public static synchronized ReplicatedIndexEventManager initIndexer(SchemaFactory<ReviewDb> schemaFactory, ChangeIndexer indexer) {
    if (instance == null || !instance.gerritIndexerRunning) {
      log.info("RC Initialising ReplicatedIndexEventManager...");
    }
    if (instance == null) {
      log.info("RC ...with a new instance....");
      //instance = new ReplicatedIndexEventManager(schema);
      instance = new ReplicatedIndexEventManager(schemaFactory, indexer);
      replicatorInstance = Replicator.getInstance(true);
      if (replicatorInstance == null) {
        // maybe we have been called only for reindex the entire Gerrit data, so we quit
        log.info("RC Replicator is null, bailing out. Setting Reindex Mode");
        instance.gerritIndexerRunning = true;
        return null;
      }

      if (instance != null) {

        Replicator.subscribeEvent(INDEX_EVENT, instance);
        Replicator.subscribeEvent(PACKFILE_EVENT, instance);

        instance.setIndexEventDirectory();
        if (indexEventReaderAndPublisherThread == null) {
          indexEventReaderAndPublisherThread = new Thread(instance);
          indexEventReaderAndPublisherThread.setName(INDEX_EVENTS_REPLICATION_THREAD_NAME);
          indexEventReaderAndPublisherThread.start();
        }

        if (changeLimiterThread == null) {
          uniqueChangesQueue = new UniqueChangesQueue();
          changeLimiterThread = new Thread(uniqueChangesQueue);
          changeLimiterThread.setName(NODUPLICATES_REINDEX_THREAD_NAME);
          changeLimiterThread.start();
        } else {
          log.error("RC Thread {} is already running!",NODUPLICATES_REINDEX_THREAD_NAME);
        }

        if (indexIncomingChangesThread == null) {
          indexIncomingChangesEvents = new IndexIncomingReplicatedEvents();
          indexIncomingChangesThread = new Thread(indexIncomingChangesEvents);
          indexIncomingChangesThread.setName(INCOMING_CHANGES_INDEX_THREAD_NAME);
          indexIncomingChangesThread.start();
        } else {
          log.error("RC Thread {} is already running!",INCOMING_CHANGES_INDEX_THREAD_NAME);
        }
      }

    }
    return instance;
  }

  /**
   * Main method used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes.
   *
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  public static void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
    queueReplicationIndexEvent(indexNumber, projectName, lastUpdatedOn, false);
  }

  /**
   * Queue a notification to be made to replica nodes regarding the deletion of an index. This must be done
   * independently of the delete() call in ChangeIndexer, as in that context, the Change is no longer
   * accessible, preventing lookup of the project name and the subsequent attempt to tie the change to
   * a specific DSM in the replicator.
   *
   * @param indexNumber
   * @param projectName
   */
  public static void queueReplicationIndexDeletionEvent(int indexNumber, String projectName) {
    queueReplicationIndexEvent(indexNumber, projectName, new Timestamp(System.currentTimeMillis()), true);
  }

  /**
   * Used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes with an additional boolean flag to indicate if the index
   * to be updated is being deleted.
   *
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  private static void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean deleteIndex) {
    if (instance == null) {
      log.error("RC ReplicatedIndexEventManager instance is null!",new IllegalStateException("Should have been initialised at this point"));
    } else if (!instance.gerritIndexerRunning)  { // we only take the event if it's normal Gerrit functioning. If it's indexing we ignore them
      for(int i =0; i < 100; i++) {  // let's try 100 times before giving up.
        try {
          IndexToReplicate indexToReplicate = new IndexToReplicate(indexNumber, projectName, lastUpdatedOn, deleteIndex);
          instance.unfilteredQueue.add(indexToReplicate);
          log.debug("RC Just added {} to cache queue",indexToReplicate);
          break;
        } catch (IllegalStateException e) {
          // The queue is full, ...
          log.error("RC error while enqueueing index event {} at {}. Trying to remove some elements out of the queue...",new Object[] {indexNumber,i},e);
          try {
            // ... try to suck some events out of it
            instance.pollAndWriteOutgoingEvents();
          } catch(InterruptedException ie) {
            log.warn("While trying to recover from full queue",ie);
          }
        }
      }
    }
  }

  private void setIndexEventDirectory() {
    if (replicatorInstance != null) {
      this.indexEventsDirectory = replicatorInstance.getIndexingEventsDirectory();
      if (this.indexEventsDirectory == null) {
        log.error("RC indexEventsDirectory is null, cannot save index events to be updated!");
      }
      if (persister == null) {
        try {
          persister = new Persister<>(replicatorInstance.getIncomingPersistedReplEventsDirectory());
          List<IndexToReplicateComparable> objectsFromPath = persister.getObjectsFromPath(IndexToReplicateComparable.class);
          incomingChangeEventsToIndex.addAll(objectsFromPath);
          log.info("Added {} existing objects in the persist directory",incomingChangeEventsToIndex.size());
        } catch (IOException e) {
          log.error("RC cannot create persisting directory for index events!",e);
        }
      }
    } else {
      log.error("RC Replicator instance is null! Maybe it's not yet been initialized.");
    }
  }

  private ReplicatedIndexEventManager(SchemaFactory<ReviewDb> schemaFactory, ChangeIndexer indexer) {
    this.schemaFactory = schemaFactory;
    this.indexer = indexer;
  }

  private static synchronized void clearThread() {
    indexEventReaderAndPublisherThread = null;
    changeLimiterThread = null;
  }

  public static ReplicatedIndexEventManager getInstance(){
    return instance;
  }

  @Override
  @SuppressWarnings("SleepWhileInLoop")
  public void run() {
    log.info("RC {} thread is starting...",INDEX_EVENTS_REPLICATION_THREAD_NAME);

    // we need to make this thread never fail, otherwise we'll lose events.
    while (!finished) {
      try {
        while (true) {
          int sentEvents = pollAndWriteOutgoingEvents();
          int workedChanges = readAndRetryIndexingChanges();
          if (finished) {
            break;
          }
          if (sentEvents == 0 && workedChanges <= 0) {
            Thread.sleep(replicatorInstance.maxSecsToWaitOnPollAndRead); // if we are not doing anything, then we should not clog the CPU
          }
        }
      } catch (InterruptedException e) {
        log.info("RC Exiting",e);
        finished = true;
      } catch(RuntimeException  e ) {
        log.error("RC Unexpected exception",e);
      } catch(Exception e) {
        log.error("RC Unexpected exception",e);
      }
    }
    log.info("RC {} thread finished",INDEX_EVENTS_REPLICATION_THREAD_NAME);
    finished = true;
    clearThread();
  }

  /**
   * Takes an event from the queue and passes it to the replicator (in gerrit) to pass it
   * to the replicator in GitMS.
   *
   * @return the number of events which have been sent to the (gerrit) replicator.
   *
   * @throws InterruptedException
   */
  private int pollAndWriteOutgoingEvents() throws InterruptedException {
    IndexToReplicate indexToReplicate;
    int removed = 0;
    while ((indexToReplicate = unfilteredQueue.poll()) != null) {
      uniqueChangesQueue.add(indexToReplicate);
      removed++;
    }
    return removed;
  }

  /**
   * Reads the events from the directory and calls the reindex function in gerrit
   * It also reindex locally the same changes it's been putting into the queue.
   * This is done to workaround a strange problem which sometimes happens: a difference
   * is seen between the original indexed change and the replicated ones. Reindexing the
   * original change will make all the nodes the same.
   *
   * @return the number of changes it successfully indexed
   */
  private int readAndRetryIndexingChanges() {
    if (!checkIndexDirectory()) {
      log.error("RC Directory {} can't be created!", indexEventsDirectory);
      return -1;
    }
    int numberOfSuccessfullyIndexedChanges = 0;
    File[] listFiles = indexEventsDirectory.listFiles(indexEventsToRetryFileFilter);

    //  listFiles can be null if the directory has disappeared, if it's not readable or if too many files are open!
    if (listFiles == null) {
      log.error("RC Directory {} cannot have files listed! (too many files open?)",indexEventsDirectory,new IllegalStateException("Cannot read index directory"));
    } else if (listFiles.length > 0) {
      log.debug("RC Found {} files", listFiles.length);
      Arrays.sort(listFiles);

      // Read each file and create a list with the changes to try reindex
      List<IndexToFile> indexList = new ArrayList<>();
      for (File file : listFiles) {
        try (InputStreamReader eventJson = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {

          IndexToReplicate index;
          try {
            index = gson.fromJson(eventJson, IndexToReplicate.class);
          } catch (JsonSyntaxException e){
            throw new InvalidEventJsonException(String.format("Index Event file contains Invalid JSON. \"%s\""
                , eventJson.toString() ));
          }

          if (index == null) {
            log.error("fromJson method returned null for file {}", eventJson);
            continue;
          }

          indexList.add(new IndexToFile(index, file));
        } catch (JsonSyntaxException | IOException | InvalidEventJsonException je) {
          log.error("RC Could not decode json file {}", file, je);
          Persister.moveFileToFailed(indexEventsDirectory, file);
        }
      }
      // Try indexing the change and record each successful try into the IndexToFile instance itself
      numberOfSuccessfullyIndexedChanges = indexChanges(indexList);
      if (numberOfSuccessfullyIndexedChanges > 0) {
        // If some indexing succeeded then try to delete the related files
        for (IndexToFile indexToFile : indexList) {
          if (indexToFile.successFul) {
            boolean deleted = indexToFile.file.delete();
            if (!deleted) {
              log.error("RC Error while deleting file {}. Please remove it!", indexToFile.file, new IllegalStateException("Cannot remove file"));
            } else {
              log.debug("RC Successfully removed file {}", indexToFile.file);
            }
          }
        }
      }
    }
    return numberOfSuccessfullyIndexedChanges;
  }

  /**
   * Looks at the queue of locally indexed changes and reindex them after the STANDARD_REINDEX_DELAY
   * This is an attempt to workaround the strange problems we've seen in gerrit
   *
   * @return  the number of successfully locally reindexed changes
   */
  private int reindexLocalData() {
    int totalDone = 0;
    int size = localReindexQueue.size();
    IndexToReplicateDelayed polled;
    Set<Integer> done = new HashSet<>();

    while ((polled = localReindexQueue.poll()) != null) {
      //log.debug("RC reindexing local {}", polled.indexNumber);
      if (!done.contains(polled.indexNumber)) {
        boolean succ = indexChange(new IndexToReplicate(polled), true);
        if (!succ) {
          log.error("RC unexpected problem while trying to reindex local change!");
        }
        done.add(polled.indexNumber);
        //log.debug("RC finished reindexing local {}", polled.indexNumber);
        totalDone++;
      }
    }
    if (totalDone > 0) {
      log.debug(String.format("RC finished to reindex local amount of %d out of %d", totalDone,size));
    }
    return totalDone;
  }

  /**
   * Called by the (gerrit) Replicator when it receives a replicated event of type INDEX_CHANGE
   * Puts the events in a queue which will be looked after by the IndexIncomingReplicatedEvents thread
   *
   * @param newEvent
   * @return success
   */
  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean success = false;

    if (newEvent != null) {
      switch (newEvent.getEventOrigin()) {
        case INDEX_EVENT:
          success = unwrapAndSendIndexEvent(newEvent);
          break;
        case PACKFILE_EVENT:
          success = unwrapAndReadPackFile(newEvent);
          break;
        default:
          log.error("RC INDEX_EVENT has been sent here but originator is not the right one ({})",newEvent.getEventOrigin());
      }
    } else {
      log.error("RC null event has been sent here");
    }
    return success;
  }

  private boolean unwrapAndSendIndexEvent(EventWrapper newEvent) throws JsonSyntaxException {
    boolean success = false;
    try {
      Class<?> eventClass = Class.forName(newEvent.getClassName());
      IndexToReplicateComparable originalEvent = null;

      try {
        IndexToReplicate index = (IndexToReplicate) gson.fromJson(newEvent.getEvent(), eventClass);

        if (index == null) {
          log.error("fromJson method returned null for {}", newEvent.toString());
          return success;
        }

        originalEvent = new IndexToReplicateComparable(index);
      } catch (JsonSyntaxException je) {
        log.error("PR Could not decode json event {}", newEvent.toString(), je);
        return success;
      }
      log.debug("RC Received this event from replication: {}",originalEvent);
      // add the data to index the change
      incomingChangeEventsToIndex.add(originalEvent);
      try {
        persister.persistIfNotAlready(originalEvent, originalEvent.projectName);
      } catch (IOException e) {
        log.error("RC Could not persist event {}",originalEvent,e);
      }
      success = true;

      synchronized(indexEventsAreReady) {
        indexEventsAreReady.notifyAll();
      }
    } catch(ClassNotFoundException e) {
      log.error("RC INDEX_EVENT has been lost. Could not find {}",newEvent.getClassName(),e);
    }
    return success;
  }

  private boolean unwrapAndReadPackFile(EventWrapper newPackFileEvent) {
    boolean success = false;

    File packFilePath = new File(newPackFileEvent.getEvent()); // we use the event member to store the path to the packfile
    File gitDir = new File(newPackFileEvent.getPrefix()); // we store the git directory in the prefix member
    log.info(String.format("RC Received packfile event from the replication. PackFile: %s, git dir: %s",packFilePath,gitDir));
    if (packFilePath.exists()) {
      final FileRepositoryBuilder builder = new FileRepositoryBuilder();
      //FileInputStream fileIn;
      final ProgressMonitor receiving = NullProgressMonitor.INSTANCE;
      final ProgressMonitor resolving = NullProgressMonitor.INSTANCE;

      Repository repo;

      try {
        // The repo must exist.
        builder.setGitDir(gitDir);
        builder.setMustExist(true);
        repo = builder.build();
      } catch (IOException e) {
        log.error("Error while initing git repo {}",gitDir,e);
        return false;
      }

      // Need an ObjectInserter to unpack the packfile into the repo
      try(FileInputStream fileIn = new FileInputStream(packFilePath);
          ObjectInserter ins = repo.newObjectInserter()) {
        // parser will parser the objects out of the packfile.
        final PackParser parser = ins.newPackParser(fileIn);
        // The packfile will be thin.
        parser.setAllowThin(true);
        parser.setNeedNewObjectIds(true);
        parser.setNeedBaseObjectIds(true);
        // Reading from a file not socket.
        parser.setCheckEofAfterPackFooter(true);
        parser.setExpectDataAfterPackFooter(false);
        parser.setObjectChecking(true);
        // no limit on the Object size.
        parser.setMaxObjectSizeLimit(0);
        // Following returns a PackLock but we are ignoring that. Packlock
        // is used by Git but we are doing our own GC.
        parser.parse(receiving, resolving);
        ins.flush();
        log.info("Successfully unpacked file {}",packFilePath);
      } catch (IOException e) {
        log.error("Error while unpacking",e);
      } finally {
        repo.close();
      }
      // Even if the packfile load was unsuccessful there is still no point to leave the packfile in the incoming directory
      // Gerrit will anyway try to rescan the git directory at a later time
      boolean deleted = packFilePath.delete();
      if (!deleted) {
        log.error("Cannot delete packfile {}",packFilePath);
      }
      success = true;
    } else {
      log.error("Packfile {} doesn't exist",packFilePath,new IllegalStateException(packFilePath.getAbsolutePath()));
    }
    return success;
  }

  private static class IndexIncomingReplicatedEvents implements Runnable {

    @Override
    public void run() {
      log.info("Thread for IndexIncomingChangesThread is starting...");
      while (!instance.finished) {
        try {
          //If a notifyAll is received from the replicator thread then
          //we will exit the synchronized block.
          synchronized(instance.indexEventsAreReady) {
            instance.indexEventsAreReady.wait(60*1000);
          }
          processIndexChangeCollection();
          // GER-638 : Taking the lock again to hold off the notifyAll in the replicator thread
          // until we are back in a waiting state. There is potential to lose events if we do not do this.
          synchronized(instance.indexEventsAreReady) {
            processIndexChangeCollection();
          }
        } catch(Exception e) {
          log.error("RC Incoming indexing event",e);
        }

      }
      log.info("Thread for IndexIncomingChangesThread ended");
    }

    private void processIndexChangeCollection() {
      NavigableMap<Change.Id,IndexToReplicateComparable> mapOfChanges = new TreeMap<>();
      IndexToReplicateComparable index;
      // make the list of changes a set of unique changes based only on the change number
      while ((index = instance.incomingChangeEventsToIndex.poll()) != null) {
        IndexToReplicateComparable old = mapOfChanges.put(new Change.Id(index.indexNumber), index);
        if (old != null) {
          // if there are many instances for the same changeid, then delete the old ones from persistence
          instance.persister.deleteFileIfPresentFor(old);
          log.info("Deleted persisted file for {}",old);
        }
      }
      if (mapOfChanges.size() > 0) {
        instance.indexCollectionOfChanges(mapOfChanges);
      }
      instance.reindexLocalData();
    }

  }

  /**
   * This will reindex changes in gerrit. Since it receives a map of changes (changeId -> IndexToReplicate) it will
   * try to understand if the index-to-replicate can be reindexed looking at the timestamp found for that ChangeId on
   * the database.
   * If it finds that the timestamp on the db is older than the one received from the replicator, then it will wait till the
   * db is updated. To compare the time we need to look at the Timezone of each modification since the sending gerrit can be
   * on a different timezone and the timestamp on the database reads differently depending on the database timezone.
   *
   * @param mapOfChanges
   */
  private void indexCollectionOfChanges(NavigableMap<Change.Id,IndexToReplicateComparable> mapOfChanges) {
    ReviewDb db = null;
    try {
      Provider<ReviewDb> dbProvider = Providers.of(schemaFactory.open());

      db = dbProvider.get();

      for (IndexToReplicateComparable i : mapOfChanges.values()) {
        if (i.delete) {
          try {
            indexer.delete(new Change.Id(i.indexNumber));
          } catch (IOException e) {
            log.error("RC Error while trying to delete change index {}",i.indexNumber,e);
          }
        }
      }

      log.debug("RC Going to index {} changes...",mapOfChanges.size());

      // fetch changes from db
      long startTime = System.currentTimeMillis();
      ResultSet<Change> changesOnDb = db.changes().get(mapOfChanges.keySet());
      long endTime = System.currentTimeMillis();
      long duration = (endTime - startTime);
      log.debug("RC Time taken to fetch changes {}", duration);

      int totalDone = 0;
      int thisNodeTimeZoneOffset = IndexToReplicate.getRawOffset(System.currentTimeMillis());
      log.debug("thisNodeTimeZoneOffset={}",thisNodeTimeZoneOffset);
      // compare changes from db with the changes landed from the index change replication
      for (Change changeOnDb: changesOnDb) {
        try {
          // If the change on the db is old (last update has been done much before the one recorded in the change,
          // the put it back in the queue
          IndexToReplicateComparable indexToReplicate = mapOfChanges.get(changeOnDb.getId());
          int landedIndexTimeZoneOffset = indexToReplicate.timeZoneRawOffset;
          log.debug("landedIndexTimeZoneOffset={}",landedIndexTimeZoneOffset);
          log.debug("indexToReplicate.lastUpdatedOn.getTime() = {}", indexToReplicate.lastUpdatedOn.getTime());

          boolean changeIndexedMoreThanXMinutesAgo = changeIndexedLastTime(thisNodeTimeZoneOffset, indexToReplicate, landedIndexTimeZoneOffset);
          log.debug("changeOnDb.getLastUpdatedOn().getTime() = {}", changeOnDb.getLastUpdatedOn().getTime());

          Timestamp normalisedChangeTimestamp = new Timestamp(changeOnDb.getLastUpdatedOn().getTime()-thisNodeTimeZoneOffset);
          Timestamp normalisedIndexToReplicate = new Timestamp(indexToReplicate.lastUpdatedOn.getTime()-landedIndexTimeZoneOffset);

          log.debug("Comparing {} to {}. MoreThan is {}",normalisedChangeTimestamp,normalisedIndexToReplicate,changeIndexedMoreThanXMinutesAgo);
          // reindex the change if it's more than an hour it's been in the queue, or if the timestamp on the database is newer than
          // the one in the change itself
          if (normalisedChangeTimestamp.before(normalisedIndexToReplicate) && !changeIndexedMoreThanXMinutesAgo) {
            instance.incomingChangeEventsToIndex.add(indexToReplicate);
            log.info("Change {} pushed back in the queue [db={}, index={}]",indexToReplicate.indexNumber,changeOnDb.getLastUpdatedOn(),indexToReplicate.lastUpdatedOn);
          } else {
            try {
              indexer.indexRepl(db, changeOnDb.getProject(), changeOnDb.getId());
              log.debug("RC Change {} INDEXED!",changeOnDb.getChangeId());
              totalDone++;
              persister.deleteFileFor(indexToReplicate);
              log.debug("changeOnDb.getId() = {} removed from mapOfChanges", changeOnDb.getId());
              mapOfChanges.remove(changeOnDb.getId());
            } catch(Exception e) { // could be org.eclipse.jgit.errors.MissingObjectException
              log.warn(String.format("Got '%s' while trying to reindex change. Requeuing",e.getMessage()),e);
              instance.incomingChangeEventsToIndex.add(indexToReplicate);
              log.info("Change {} pushed back in the queue",indexToReplicate.indexNumber);
            }
          }
        } catch(Exception e) {
          log.error("RC Error while trying to reindex change {}",changeOnDb.getChangeId(),e);
        }
      }

      // Check for files that have remained too long and are no longer valid
      // because they are no longer found in the database
      removeStaleIndexes(mapOfChanges);

      log.debug(String.format("RC Finished indexing %d changes... (%d)",mapOfChanges.size(), totalDone));
    } catch (OrmException e) {
      log.error("RC Error while trying to reindex change", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  /**
   * Calculates the time when the change was last indexed and works
   * out whether it has been over the amount of minutes specified by the
   * value provided in the configurable (gerrit.minutes.since.last.index.check.period)
   * @param thisNodeTimeZoneOffset
   * @param indexToReplicate
   * @param landedIndexTimeZoneOffset
   * @return
   */
  public boolean changeIndexedLastTime(long thisNodeTimeZoneOffset,
                                         IndexToReplicate indexToReplicate, long landedIndexTimeZoneOffset ){
    return (System.currentTimeMillis()-thisNodeTimeZoneOffset
        - (indexToReplicate.lastUpdatedOn.getTime()-landedIndexTimeZoneOffset)) >
        replicatorInstance.getMinutesSinceChangeLastIndexedCheckPeriod();
  }

  /**
   * This will remove old files in the incoming-persisted directory if they cannot be found in the DB
   * and they have existed for X amount of time
   *
   * @param mapOfChanges not found in DB
   */
  private void removeStaleIndexes(NavigableMap<Change.Id,IndexToReplicateComparable> mapOfChanges) {

    // lazy initialization of linger time
    if (INCOMING_PERSISTED_LINGER_TIME_VALUE == 0) {
      Config config = ReplicatedEventsManager.getGerritConfig();

      if (config != null) {
        INCOMING_PERSISTED_LINGER_TIME_VALUE = config.getLong("wandisco", null,
          INCOMING_PERSISTED_LINGER_TIME_KEY, INCOMING_PERSISTED_LINGER_TIME_DEFAULT);
      } else {
        INCOMING_PERSISTED_LINGER_TIME_VALUE = INCOMING_PERSISTED_LINGER_TIME_DEFAULT;
      }
    }

    // work out time zone and offSet from UTC
    TimeZone timeZone = TimeZone.getDefault();
    long offSet = timeZone.getOffset(System.currentTimeMillis());

    long currentTime = System.currentTimeMillis() - offSet;

    for (IndexToReplicateComparable current : mapOfChanges.values()) {
      if ((current.lastUpdatedOn.getTime() - current.timeZoneRawOffset) + INCOMING_PERSISTED_LINGER_TIME_VALUE < currentTime) {
        log.debug(String.format("Removing stale index file %s ", current.persistFile.getName()));
        persister.deleteFileFor(current);
      }
    }

  }

  /**
   * This will index the change calling the internal function of Gerrit, but only
   * if the data provided in the indexEvent has a timestamp compatible to be index (i.e. it
   * is younger that the data read from the current database)
   *
   * @param indexEvent
   */
  private boolean indexChange(IndexToReplicate indexEvent, boolean forceIndexing) {
    ReviewDb db = null;
    boolean success = false;
    try {
      Provider<ReviewDb> dbProvider = Providers.of(schemaFactory.open());

      db = dbProvider.get();
      success = indexSingleChange(db, indexEvent,true, forceIndexing);
    } catch (OrmException e) {
      log.error("RC Error while trying to reindex change", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
    return success;
  }

  /**
   * Tries to index the changes in the list. Each successful index will be recorded in the IndexOfFile itself
   *
   * @param indexToFileList
   * @return result
   */
  private int indexChanges(List<IndexToFile> indexToFileList) {
    ReviewDb db = null;
    int result = 0;
    try {
      Provider<ReviewDb> dbProvider = Providers.of(schemaFactory.open());

      db = dbProvider.get();

      Set<Integer> done = new HashSet<>() ;

      for (IndexToFile indexToFile: indexToFileList) {
        Integer indexNumber = indexToFile.getIndexNumber();
        if (done.contains(indexNumber)) {
          log.debug("RC Change {} has already been INDEXED!",indexNumber);
          indexToFile.successFul = true;
          result++;
        } else {
          boolean indexSuccess = indexSingleChange(db, indexToFile.indexChangeRetrier,false,false);
          if (indexSuccess) {
            result++;
            indexToFile.successFul = true;
            done.add(indexNumber);
          }
        }
      }
    } catch (OrmException e) {
      log.error("RC Error while trying to reindex change", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
    return result;
  }

  private boolean indexSingleChange(ReviewDb db, IndexToReplicate indexEvent, boolean enqueueIfUnsuccessful, boolean forceIndexing) throws OrmException {

    long startTime = System.currentTimeMillis();
    Change change = db.changes().get(new Change.Id(indexEvent.indexNumber));
    long endTime = System.currentTimeMillis();
    long duration = (endTime - startTime);
    log.debug("RC Lookup of change.Id {} took {}",indexEvent.indexNumber, duration);

    if (change == null) {
      log.info("RC Change {} not reindexed, not found -- deleted",indexEvent.indexNumber);
      return true;
    }

    // Each change has a timestamp which is the time when it was last modified.
    // If on the database we don't have an update-date >= timestamp, then we wait until the
    if (!forceIndexing && change.getLastUpdatedOn().before(indexEvent.lastUpdatedOn)) {
      // we need to wait until the change has been updated
      log.info("RC Change {} is still to be updated ****************** ",indexEvent.indexNumber);
      if (enqueueIfUnsuccessful) {
        enqueue(indexEvent);
      }
    } else {
      log.debug("RC Change {} can be INDEXED! ({})",indexEvent.indexNumber,forceIndexing);
      try {
        indexer.indexRepl(db, change.getProject(), change.getId());
        log.debug("RC Change {} SUCCESSFULLY INDEXED!",indexEvent.indexNumber);
        return true;
      } catch(IOException e) {
        log.error("RC Error while trying to reindex change {}",indexEvent.indexNumber,e);
      }
    }
    return false;
  }

  /**
   * This will write the indexEvent to disk to be handled by the thread responsible of
   * retrying to index the change again when the data is up to date in the database
   *
   * @param indexEvent
   */
  private void enqueue(IndexToReplicate indexEvent) {
    String name =  String.format("I-%s-%08d.json",indexEvent.indexNumber,counter.incrementAndGet());


    File newIndexFile = new File(indexEventsDirectory,name);
    if (!checkIndexDirectory()) {
      log.error("RC Cannot enqueue index events, no temp directory available");
    } else {
      log.info("RC queueing no {}",indexEvent.indexNumber);
      try {
        File tempFile = File.createTempFile("temp", ".txt", indexEventsDirectory);

        try (FileOutputStream f = new FileOutputStream(tempFile)) {
          f.write((gson.toJson(indexEvent)+"\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!tempFile.renameTo(newIndexFile)) {
          log.error("RC Could not rename {} to {}", new Object[] {tempFile, newIndexFile}, new IOException("RC Error while renaming!"));
        } else {
          log.debug("RC Created index-event file {}",newIndexFile);
        }
      } catch (IOException e) {
          log.error("RC Error while storing the index event on the file system. Event will not be lost!",e);
      }
    }
  }

  boolean checkIndexDirectory() {
    if (!indexEventsDirectory.exists()) {
      if (!indexEventsDirectory.mkdirs()) {
        log.error("RC {} path cannot be created! Index events will not work!",indexEventsDirectory.getAbsolutePath());
        return false;
      }

      log.info("RC {} created.",indexEventsDirectory.getAbsolutePath());

    }
    return true;
  }

  static class IndexToFile {
    IndexToReplicate indexChangeRetrier;
    File file;
    boolean successFul;

    private IndexToFile(IndexToReplicate index, File file) {
      this.indexChangeRetrier = index;
      this.file = file;
      successFul = false;
    }

    int getIndexNumber() {
      return indexChangeRetrier.indexNumber;
    }
  }

  final static class IndexEventsToRetryFileFilter implements FileFilter {
    // These values are just to do a minimal filtering
    static final String FIRST_PART = "I-";
    static final String LAST_PART = ".json";

    @Override
    public boolean accept(File pathname) {
      String name = pathname.getName();
      try {
        if (name.startsWith(FIRST_PART) && name.endsWith(LAST_PART)) {
          return true;
        }
      } catch (Exception e) {
        log.error("RC File {} is not allowed here, remove it please ",pathname,e);
      }
      return false;
    }
  }

  /**
   * Holds information needed to index the change on the nodes, and also to make it replicate across the other nodes
   */
  public static final class IndexToReplicateDelayed implements Delayed {
    public final int indexNumber;
    public final String projectName;
    public final Timestamp lastUpdatedOn;
    public final long currentTime;
    public static final long STANDARD_REINDEX_DELAY = 30*1000; // 30 seconds

    public IndexToReplicateDelayed(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
      this.indexNumber = indexNumber;
      this.projectName = projectName;
      this.lastUpdatedOn = new Timestamp(lastUpdatedOn.getTime());
      this.currentTime = System.currentTimeMillis();
    }

    private IndexToReplicateDelayed(IndexToReplicate index) {
      this.indexNumber = index.indexNumber;
      this.projectName = index.projectName;
      this.lastUpdatedOn = index.lastUpdatedOn;
      this.currentTime = index.currentTime;
    }

    private static IndexToReplicateDelayed shallowCopyOf(IndexToReplicate indexToReplicate) {
      return new IndexToReplicateDelayed(indexToReplicate);
    }

    @Override
    public String toString() {
      return "IndexToReplicate{" + "indexNumber=" + indexNumber + ", projectName=" + projectName + ", lastUpdatedOn=" + lastUpdatedOn + ", currentTime=" + currentTime + '}';
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(STANDARD_REINDEX_DELAY - (System.currentTimeMillis()-currentTime), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      int result;
      if (o == null) {
        result = 1;
      } else {
        long diff = this.currentTime - ((IndexToReplicateDelayed) o).currentTime ;
        result = diff < 0 ? -1: diff==0? (this.indexNumber - ((IndexToReplicateDelayed) o).indexNumber):+1;
      }
      return result;
    }

    @Override
    public int hashCode() {
      int hash = 3;
      hash = 41 * hash + this.indexNumber;
      hash = 41 * hash + Objects.hashCode(this.projectName);
      hash = 41 * hash + Objects.hashCode(this.lastUpdatedOn);
      hash = 41 * hash + (int) (this.currentTime ^ (this.currentTime >>> 32));
      return hash;
    }


    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final IndexToReplicate other = (IndexToReplicate) obj;
      if (this.indexNumber != other.indexNumber) {
        return false;
      }
      if (!Objects.equals(this.projectName, other.projectName)) {
        return false;
      }
      if (!Objects.equals(this.lastUpdatedOn, other.lastUpdatedOn)) {
        return false;
      }
      return this.currentTime == other.currentTime;
    }

  }
  /**
   * Holds information needed to index the change on the nodes, and also to make it replicate across the other nodes
   */
  public static class IndexToReplicate extends ReplicatedEvent /*implements Comparable<IndexToReplicate>*/ {
    public int indexNumber;
    public String projectName;
    public Timestamp lastUpdatedOn;
    public long currentTime;
    public static long STANDARD_REINDEX_DELAY = 30*1000; // 30 seconds
    public int timeZoneRawOffset;
    public boolean delete;


    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
      super(replicatorInstance.getThisNodeIdentity());
      final long currentTimeMs = super.getEventTimestamp();
      setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), false);
    }

    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean delete) {
      super(replicatorInstance.getThisNodeIdentity());
      final long currentTimeMs = super.getEventTimestamp();
      setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), delete);
    }

    private IndexToReplicate(IndexToReplicateDelayed delayed) {
      this(delayed.indexNumber, delayed.projectName, delayed.lastUpdatedOn, delayed.currentTime, getRawOffset(delayed.currentTime), false);
    }

    protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset, boolean delete) {
      super(replicatorInstance.getThisNodeIdentity());
      setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTime, rawOffset, delete);
    }

    private void setBaseMembers(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset, boolean delete) {
      this.indexNumber = indexNumber;
      this.projectName = projectName;
      this.lastUpdatedOn = new Timestamp(lastUpdatedOn.getTime());
      this.currentTime = currentTime;
      this.timeZoneRawOffset = rawOffset;
      this.delete = delete;
    }

    protected static int getRawOffset(final long currentTime) {
      TimeZone tzDefault = TimeZone.getDefault();

      if (tzDefault.inDaylightTime(new Date(currentTime))) {
        return TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings();
      }

      return TimeZone.getDefault().getRawOffset();
    }

    @Override public String toString() {
      final StringBuilder sb = new StringBuilder("IndexToReplicate{");
      sb.append("indexNumber=").append(indexNumber);
      sb.append(", projectName='").append(projectName).append('\'');
      sb.append(", lastUpdatedOn=").append(lastUpdatedOn);
      sb.append(", currentTime=").append(currentTime);
      sb.append(", timeZoneRawOffset=").append(timeZoneRawOffset);
      sb.append(", delete=").append(delete);
      sb.append(", ").append(super.toString());
      sb.append('}');
      return sb.toString();
    }

    @Override
    public int hashCode() {
      int hash = 3;
      hash = 41 * hash + this.indexNumber;
      hash = 41 * hash + Objects.hashCode(this.projectName);
      hash = 41 * hash + Objects.hashCode(this.lastUpdatedOn);
      hash = 41 * hash + (int) (this.currentTime ^ (this.currentTime >>> 32));
      return hash;
    }


    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final IndexToReplicate other = (IndexToReplicate) obj;
      if (this.indexNumber != other.indexNumber) {
        return false;
      }
      if (!Objects.equals(this.projectName, other.projectName)) {
        return false;
      }
      if (!Objects.equals(this.lastUpdatedOn, other.lastUpdatedOn)) {
        return false;
      }
      return this.currentTime == other.currentTime;
    }
  }

  /**
   * Implementation which only takes the changeNumber as main comparison operator
   */
  public static final class IndexToReplicateComparable extends IndexToReplicate implements Comparable<IndexToReplicate>, Persistable {
    File persistFile = null;

    public IndexToReplicateComparable(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
      super(indexNumber, projectName, lastUpdatedOn);
    }

    public IndexToReplicateComparable(IndexToReplicate index) {
      super(index.indexNumber, index.projectName, index.lastUpdatedOn, index.currentTime, index.timeZoneRawOffset, index.delete);
    }
    @Override
    public int hashCode() {
      int hash = 3;
      return 41 * hash + this.indexNumber;
    }
    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final IndexToReplicate other = (IndexToReplicate) obj;
      return (this.indexNumber == other.indexNumber);
    }

    @Override
    public int compareTo(IndexToReplicate o) {
      if (o == null) {
        return 1;
      }

      return this.indexNumber - o.indexNumber;
    }

    @Override
    public String toString() {
      return "IndexToReplicateComparable " + super.toString();
    }

    @Override
    public File getPersistFile() {
      return persistFile;
    }

    @Override
    public void setPersistFile(File file) {
      this.persistFile = file;
    }

    @Override
    public boolean hasBeenPersisted() {
      return this.persistFile != null;
    }

  }
  /**
   * Uses a TreeSet and a ConcurrentLinkedQueue to limit the number of duplicated changes to be sent for replication.
   * Only send list of changes to reindex every 30 seconds
   */
  public static class UniqueChangesQueue implements Runnable {
    private final ConcurrentLinkedQueue<IndexToReplicate> filteredQueue =   new ConcurrentLinkedQueue<>();
    private final Set<Integer> changeSet = new TreeSet<>(); // used to avoid duplicates

    public boolean add(IndexToReplicate index) {
      synchronized(changeSet) {
        // Added the OR block below as part of GER-530. The index for the 'draft change to delete' was not
        // being added to the queue because a different event with the same index number already existed.
        // We need to make sure our delete event always gets added so that the replication can happen.
        if (changeSet.add(index.indexNumber) || index.delete) {
          filteredQueue.add(index);
          return true;
        }
      }
      return false;
    }

    @Override
    @SuppressWarnings("SleepWhileInLoop")
    public void run() {
      IndexToReplicate indexToReplicate;

      log.info("RC filtered queue thread starting...");
      while (!instance.finished) {
        try {
          int eventsGot = 0;
          synchronized(changeSet) {
            while ((indexToReplicate = filteredQueue.poll()) != null) {
              replicatorInstance.queueEventForReplication(GerritEventFactory.createReplicatedIndexEvent(indexToReplicate));
              instance.localReindexQueue.add(IndexToReplicateDelayed.shallowCopyOf(indexToReplicate));
              changeSet.remove(indexToReplicate.indexNumber);
              eventsGot++;
            }
          }
          if (eventsGot > 0) {
            log.debug(String.format("RC Sent %d elements from the queue",eventsGot));
          }
          // The collection (queue) of changes is effective only if many of them are collected for uniqueness.
          // So it's worth waiting in the loop to make them build up in the queue, to avoid sending duplicates around
          // If we send them right away we don't know if we are sending around duplicates.
          Thread.sleep(replicatorInstance.getReplicatedIndexUniqueChangesQueueWaitTime());
        } catch (InterruptedException ex) {
          break;
        } catch(Exception e) {
          log.error("RC Inside the queue thread", e);
        }
      }
      log.info("RC filtered queue thread finished");
    }
  }

  /**
   * Replicate the changes that are to be deleted when we preserve a replicated repo
   * Takes a list of int's
   * @throws IOException
   */
  public void deleteChanges(int[] changes) throws IOException{
    //iterate over the list of changes and delete each one
    for(int i: changes) {
      indexer.delete(new Change.Id(i));
      log.debug("Deleted change " + i);
    }
  }

  /**
   * Replicate the changes that are to be deleted when we delete a replicated repo.
   * Takes a list of ChangeData
   * @throws IOException
   */
  public void deleteChanges(List<ChangeData> changes) throws IOException{
    //iterate over the list of changes and delete each one
    for (ChangeData cd: changes) {
      indexer.delete(cd.getId());
      log.debug(("Deleted change " + cd.getId().toString()));
    }
  }
}
