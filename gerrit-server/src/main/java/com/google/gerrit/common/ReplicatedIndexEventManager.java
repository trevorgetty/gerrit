package com.google.gerrit.common;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
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
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PackParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  public static final String INDEX_EVENTS_REINDEX_THREAD_NAME = "ReIndexEventsThread";
  public static final String NODUPLICATES_REINDEX_THREAD_NAME = "ReIndexEventsNoDuplicatesThread";
  public static final String INCOMING_CHANGES_INDEX_THREAD_NAME = "IncomingChangesChangeIndexerThread";
  public static final Random RANDOM = new Random();
  
  
  private static final Logger log = LoggerFactory.getLogger(ReplicatedIndexEventManager.class);
  private static final Gson gson = new Gson();
  private static ReplicatedIndexEventManager instance = null;
  private static Thread indexEventReaderAndPublisherThread = null;

  private boolean finished = false;
  private static final long maxSecsToWaitForEventOnQueue=15;
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
  
  public static synchronized ReplicatedIndexEventManager initIndexer(SchemaFactory<ReviewDb> schemaFactory, ChangeIndexer indexer) {
    if (instance == null || !instance.gerritIndexerRunning) {
      log.info("RC Initialising ReplicatedIndexEventManager...");
    }
    if (instance == null) {
      log.info("RC ...with a new instance....");
      //instance = new ReplicatedIndexEventManager(schema);
      instance = new ReplicatedIndexEventManager(schemaFactory, indexer);
      replicatorInstance = Replicator.getInstance(false);
      if (replicatorInstance == null) {
        // maybe we have been called only for reindex the entire Gerrit data, so we quit
        log.info("RC Replicator is null, bailing out. Setting Reindex Mode");
        instance.gerritIndexerRunning = true;
        return null;
      }
      Replicator.subscribeEvent(EventWrapper.Originator.INDEX_EVENT, instance);
      Replicator.subscribeEvent(EventWrapper.Originator.PACKFILE_EVENT, instance);
        
      if (instance != null) {
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
    queueReplicationIndexEvent(indexNumber, projectName, new Timestamp(0), true);
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
            Thread.sleep(1000); // if we are not doing anything, then we should not clog the CPU
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
    log.debug("RC looking for index files...");
    File[] listFiles = indexEventsDirectory.listFiles(indexEventsToRetryFileFilter);
    
    //  listFiles can be null if the directory has disappeared, if it's not readable or if too many files are open!
    if (listFiles == null) {
      log.error("RC Directory {} cannot have files listed! (too many files open?)",indexEventsDirectory,new IllegalStateException("Cannot read index directory"));
    } else if (listFiles.length > 0) {
      log.debug("RC Found {} files", listFiles.length);

      // Read each file and create a list with the changes to try reindex
      List<IndexToFile> indexList = new ArrayList<>();
      for (File file : listFiles) {
        try (InputStreamReader fileToRead = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {
          IndexToReplicate index = gson.fromJson(fileToRead, IndexToReplicate.class);
          indexList.add(new IndexToFile(index, file));
        } catch (IOException e) {
          log.error("RC Could not decode json file {}", file, e);
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
   * @return 
   */
  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean success = false;
    
    if (newEvent != null) {
      switch (newEvent.originator) {
        case INDEX_EVENT:
          success = unwrapAndSendIndexEvent(newEvent);
          break;
        case PACKFILE_EVENT:
          success = unwrapAndReadPackFile(newEvent);
          break;
        default:
          log.error("RC INDEX_EVENT has been sent here but originator is not the right one ({})",newEvent.originator);
      }
    } else {
      log.error("RC null event has been sent here");
    }
    return success;
  }

  private boolean unwrapAndSendIndexEvent(EventWrapper newEvent) throws JsonSyntaxException {
    boolean success = false;
    try {
      Class<?> eventClass = Class.forName(newEvent.className);
      IndexToReplicateComparable originalEvent = new IndexToReplicateComparable((IndexToReplicate) gson.fromJson(newEvent.event, eventClass));
      log.debug("RC Received this event from replication: {}",originalEvent);
      // add the data to index the change
      incomingChangeEventsToIndex.add(originalEvent);
      try {
        persister.persistIfNotAlready(originalEvent);
      } catch (IOException e) {
        log.error("RC Could not persist event {}",originalEvent,e);
      }
      success = true;

      synchronized(indexEventsAreReady) {
        indexEventsAreReady.notifyAll();
      }
    } catch(ClassNotFoundException e) {
      log.error("RC INDEX_EVENT has been lost. Could not find {}",newEvent.className,e);
    }
    return success;
  }

  private boolean unwrapAndReadPackFile(EventWrapper newPackFileEvent) {
    boolean success = false;

    File packFilePath = new File(newPackFileEvent.event); // we use the event member to store the path to the packfile
    File gitDir = new File(newPackFileEvent.prefix); // we store the git directory in the prefix member
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
          synchronized(instance.indexEventsAreReady) {
            instance.indexEventsAreReady.wait(60*1000); // events can be re-queued, so it is worth rechecking every now and then
          }
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
        } catch(Exception e) {
          log.error("RC Incoming indexing event",e);
        }
      }
      log.info("Thread for IndexIncomingChangesThread ended");
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
      ResultSet<Change> changesOnDb = db.changes().get(mapOfChanges.keySet());
      
      int totalDone = 0;
      int thisNodeTimeZoneOffset = TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings();
      log.debug("thisNodeTimeZoneOffset={}",thisNodeTimeZoneOffset);
      // compare changes from db with the changes landed from the index change replication
      for (Change changeOnDb: changesOnDb) {
        try {
          // If the change on the db is old (last update has been done much before the one recorded in the change,
          // the put it back in the queue
          IndexToReplicateComparable indexToReplicate = mapOfChanges.get(changeOnDb.getId());
          int landedIndexTimeZoneOffset = indexToReplicate.timeZoneRawOffset;
          log.debug("landedIndexTimeZoneOffset={}",landedIndexTimeZoneOffset);
          
          boolean changeIndexedMoreThanOneHourAgo = 
              (System.currentTimeMillis()-thisNodeTimeZoneOffset 
              - (indexToReplicate.lastUpdatedOn.getTime()-landedIndexTimeZoneOffset)) > 3600*1000;
          
          Timestamp normalisedChangeTimestamp = new Timestamp(changeOnDb.getLastUpdatedOn().getTime()-thisNodeTimeZoneOffset);
          Timestamp normalisedIndexToReplicate = new Timestamp(indexToReplicate.lastUpdatedOn.getTime()-landedIndexTimeZoneOffset);
          
          log.debug("Comparing {} to {}. MoreThan is {}",normalisedChangeTimestamp,normalisedIndexToReplicate,changeIndexedMoreThanOneHourAgo);
          // reindex the change if it's more than an hour it's been in the queue, or if the timestamp on the database is newer than
          // the one in the change itself
          if (normalisedChangeTimestamp.before(normalisedIndexToReplicate) && !changeIndexedMoreThanOneHourAgo) {
            instance.incomingChangeEventsToIndex.add(indexToReplicate);
            log.info("Change {} pushed back in the queue [db={}, index={}]",indexToReplicate.indexNumber,changeOnDb.getLastUpdatedOn(),indexToReplicate.lastUpdatedOn);
          } else {
            try {
              indexer.indexRepl(db,changeOnDb);
              log.debug("RC Change {} INDEXED!",changeOnDb.getChangeId());
              totalDone++;
              persister.deleteFileFor(indexToReplicate);
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
   * @return 
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
    Change change = db.changes().get(new Change.Id(indexEvent.indexNumber));

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
        indexer.indexRepl(db, change);
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
      } else {
        log.info("RC {} created.",indexEventsDirectory.getAbsolutePath());
      }
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
  public static class IndexToReplicate /*implements Comparable<IndexToReplicate>*/ {
    public final int indexNumber;
    public final String projectName;
    public final Timestamp lastUpdatedOn;
    public final long currentTime;
    public static final long STANDARD_REINDEX_DELAY = 30*1000; // 30 seconds
    public final int timeZoneRawOffset;
    public final boolean delete;

    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
      this(indexNumber, projectName, lastUpdatedOn, System.currentTimeMillis(), 
              TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings(), false);
    }
    
    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean delete) {
      this(indexNumber, projectName, lastUpdatedOn, System.currentTimeMillis(), 
              TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings(), delete);
    }

    protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime) {
      this(indexNumber, projectName, lastUpdatedOn, currentTime, 
              TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings(), false);
    }

    private IndexToReplicate(IndexToReplicateDelayed delayed) {
      this(delayed.indexNumber, delayed.projectName, delayed.lastUpdatedOn, 
              delayed.currentTime, TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings(), false);
    }

    protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset) {
      this(indexNumber, projectName, lastUpdatedOn, currentTime, rawOffset, false);
    }
    
    protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset, boolean delete) {
      this.indexNumber = indexNumber;
      this.projectName = projectName;
      this.lastUpdatedOn = new Timestamp(lastUpdatedOn.getTime());
      this.currentTime = currentTime;
      this.timeZoneRawOffset = rawOffset;
      this.delete = delete;
    }

    @Override
    public String toString() {
      return "IndexToReplicate{" + "indexNumber=" + indexNumber + ", projectName=" 
              + projectName + ", lastUpdatedOn=" + lastUpdatedOn + ", currentTime=" 
              + currentTime + ", timeZoneRawOffset=" + timeZoneRawOffset + ", delete="
              + delete + '}';
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
    public IndexToReplicateComparable(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime) {
      super(indexNumber, projectName, lastUpdatedOn, currentTime);
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
      } else {
        return this.indexNumber - o.indexNumber;
      }
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
        if (changeSet.add(index.indexNumber)) {
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
              replicatorInstance.queueEventForReplication(new EventWrapper(indexToReplicate));
              instance.localReindexQueue.add(IndexToReplicateDelayed.shallowCopyOf(indexToReplicate));
              changeSet.remove(indexToReplicate.indexNumber);
              eventsGot++;
            }
          }
          if (eventsGot > 0) {
            log.debug(String.format("RC Sent %d elements from the queue",eventsGot));
          }
          // The collection (queue) of changes is effective only if many of them are collected for uiniqueness.
          // So it's worth waiting in the loop to make them build up in the queue, to avoid sending duplicates around
          // If we send them right away we don't know if we are sending around duplicates.
          Thread.sleep(20*1000);
        } catch (InterruptedException ex) {
          break;
        } catch(Exception e) {
          log.error("RC Inside the queue thread", e);
        }
      }
      log.info("RC filtered queue thread finished");
    }
  }
}
