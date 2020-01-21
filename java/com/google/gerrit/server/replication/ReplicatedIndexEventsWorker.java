package com.google.gerrit.server.replication;

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.*;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.PACKFILE_EVENT;

import com.wandisco.gerrit.gitms.shared.events.PackFileEvent;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PackParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ReplicatedIndexEventsWorker implements Runnable, Replicator.GerritPublishable {


  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  // readonly constants
  private static final String INDEX_EVENTS_REPLICATION_THREAD_NAME = "IndexEventsReplicationThread";
  private static final String NODUPLICATES_REINDEX_THREAD_NAME = "ReIndexEventsNoDuplicatesThread";
  private static final String INCOMING_CHANGES_INDEX_THREAD_NAME = "IncomingChangesChangeIndexerThread";

  private static final int MAX_EVENTS = 8 * 1024; // Maximum number of events that may be queued up

  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();

  private ReplicatedIndexEventManager replicatedIndexEventsManager;
  private UniqueChangesQueue uniqueChangesQueue = null;
  private Thread indexEventReaderAndPublisherThread = null;
  private final IndexEventsToRetryFileFilter indexEventsToRetryFileFilter = new IndexEventsToRetryFileFilter();

  private boolean finished = false;
  private boolean dontReplicateIndexEvents = false;

  private File indexEventsDirectory = null;
  private final AtomicInteger counter = new AtomicInteger();

  private final DelayQueue<IndexToReplicateDelayed> localReindexQueue = new DelayQueue<>();

  // Queue o// Maximum number of events that may be queued upf events to replicate (first queue to filter out duplicates using a Set)
  private final LinkedBlockingQueue<IndexToReplicate> unfilteredQueue = new LinkedBlockingQueue<>(MAX_EVENTS);

  private Thread changeLimiterThread = null;
  private IndexIncomingReplicatedEvents indexIncomingChangesEvents;
  private Thread indexIncomingChangesThread = null;
  private ConcurrentLinkedQueue<IndexToReplicateComparable> incomingChangeEventsToIndex = new ConcurrentLinkedQueue<>();
  private final Object indexEventsAreReady = new Object();
  private Persister<IndexToReplicateComparable> persister;
  private Replicator replicatorInstance;
  private ChangeIndexer indexer;

  public ReplicatedIndexEventsWorker(ReplicatedIndexEventManager replicatedIndexEventManager, ChangeIndexer indexer) {
    this.replicatedIndexEventsManager = replicatedIndexEventManager;
    this.indexer = indexer;
  }

  public void start() {
    if (Replicator.isReplicationDisabled()) {
      dontReplicateIndexEvents = true;
      return;
    }

    logger.atInfo().log("RC starting ReplicatedIndexEventWorker...");
    replicatorInstance = Replicator.getInstance(true);

    if (replicatorInstance == null) {
      // maybe we have been called only for reindex the entire Gerrit data, so we quit
      logger.atInfo().log("RC Replicator is null, bailing out. Setting Reindex Mode to non replicated.");
      dontReplicateIndexEvents = true;
      return;
    }

    Replicator.subscribeEvent(INDEX_EVENT, this);
    Replicator.subscribeEvent(PACKFILE_EVENT, this);

    setIndexEventDirectory();

    // Start each of our replication threads in turn....
    indexEventReaderAndPublisherThread = new Thread(this);
    indexEventReaderAndPublisherThread.setName(INDEX_EVENTS_REPLICATION_THREAD_NAME);
    indexEventReaderAndPublisherThread.start();

    uniqueChangesQueue = new UniqueChangesQueue();
    changeLimiterThread = new Thread(uniqueChangesQueue);
    changeLimiterThread.setName(NODUPLICATES_REINDEX_THREAD_NAME);
    changeLimiterThread.start();

    indexIncomingChangesEvents = new IndexIncomingReplicatedEvents();
    indexIncomingChangesThread = new Thread(indexIncomingChangesEvents);
    indexIncomingChangesThread.setName(INCOMING_CHANGES_INDEX_THREAD_NAME);
    indexIncomingChangesThread.start();
  }


  public synchronized void stop() {
    if (!finished) {
      finished = true;
      Replicator.unsubscribeEvent(INDEX_EVENT, this);
      Replicator.unsubscribeEvent(PACKFILE_EVENT, this);
    }
  }

  /** Utility method to work out time offset with timezone
   *
   * @param currentTime
   * @return
   */
  public static int getRawOffset(final long currentTime) {
    TimeZone tzDefault = TimeZone.getDefault();

    if (tzDefault.inDaylightTime(new Date(currentTime))) {
      return TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings();
    }

    return TimeZone.getDefault().getRawOffset();
  }

  private void setIndexEventDirectory() {
    if (replicatorInstance == null) {
      // replication is disabled, just exit.
      logger.atSevere().log("RC Replicator instance is null! Maybe it's not yet been initialized.");
      throw new RuntimeException("Replicator instance not available for ReplicatedIndexWorker to proceed");
    }

    this.indexEventsDirectory = replicatorInstance.getIndexingEventsDirectory();
    if (this.indexEventsDirectory == null) {
      logger.atSevere().log("RC indexEventsDirectory is null, cannot save index events to be updated!");
    }
    if (persister == null) {
      try {
        persister = new Persister<>(replicatorInstance.getIncomingPersistedReplEventsDirectory());
        List<IndexToReplicateComparable> objectsFromPath = persister.getObjectsFromPath(IndexToReplicateComparable.class);
        incomingChangeEventsToIndex.addAll(objectsFromPath);
        logger.atInfo().log("Added %d existing objects in the persist directory", incomingChangeEventsToIndex.size());
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("RC cannot create persisting directory for index events!");
      }
    }
  }

  private synchronized void clearThread() {
    indexEventReaderAndPublisherThread = null;
    changeLimiterThread = null;
  }

  @Override
  @SuppressWarnings("SleepWhileInLoop")
  public void run() {
    logger.atInfo().log("RC %s thread is starting...", INDEX_EVENTS_REPLICATION_THREAD_NAME);

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
        logger.atInfo().withCause(e).log("RC Exiting");
        finished = true;
      } catch (RuntimeException e) {
        logger.atSevere().withCause(e).log("RC Unexpected exception");
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("RC Unexpected exception");
      }
    }
    logger.atInfo().log("RC %s thread finished", INDEX_EVENTS_REPLICATION_THREAD_NAME);
    finished = true;
    clearThread();
  }

  /**
   * Takes an event from the queue and passes it to the replicator (in gerrit) to pass it
   * to the replicator in GitMS.
   *
   * @return the number of events which have been sent to the (gerrit) replicator.
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
      logger.atSevere().log("RC Directory %s can't be created!", indexEventsDirectory);
      return -1;
    }
    int numberOfSuccessfullyIndexedChanges = 0;
    logger.atFiner().log("RC looking for index files...");
    File[] listFiles = indexEventsDirectory.listFiles(indexEventsToRetryFileFilter);

    //  listFiles can be null if the directory has disappeared, if it's not readable or if too many files are open!
    if (listFiles == null) {
      logger.atSevere().withCause(new IllegalStateException("Cannot read index directory")).log(
          "RC Directory %s cannot have files listed! (too many files open?)", indexEventsDirectory);

    } else if (listFiles.length > 0) {
      logger.atFiner().log("RC Found %s files", listFiles.length);
      Arrays.sort(listFiles);

      // Read each file and create a list with the changes to try reindex
      List<IndexToFile> indexList = new ArrayList<>();
      for (File file : listFiles) {
        try (InputStreamReader fileToRead = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
          IndexToReplicate index = gson.fromJson(fileToRead, IndexToReplicate.class);
          if (index == null) {
            logger.atSevere().log("fromJson method returned null for file %s", fileToRead);
            continue;
          }

          indexList.add(new IndexToFile(index, file));
        } catch (JsonSyntaxException e) {
          logger.atSevere().withCause(e).log("RC Could not decode json file %s", file);
          Persister.moveFileToFailed(indexEventsDirectory, file);
        } catch (IOException e) {
          logger.atSevere().withCause(e).log("RC Could not decode json file %s", file);
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
              logger.atSevere().withCause(new IllegalStateException("Cannot remove file")).log(
                  "RC Error while deleting file %s. Please remove it!", indexToFile.file);
            } else {
              logger.atFiner().log("RC Successfully removed file %s", indexToFile.file);
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
   * @return the number of successfully locally reindexed changes
   */
  private int reindexLocalData() {
    int totalDone = 0;
    int size = localReindexQueue.size();
    IndexToReplicateDelayed polled;
    Set<Integer> done = new HashSet<>();

    while ((polled = localReindexQueue.poll()) != null) {
      logger.atFiner().log("RC reindexing local %s", polled.indexNumber);
      if (!done.contains(polled.indexNumber)) {
        boolean succ = indexChange(new IndexToReplicate(polled), true);
        if (!succ) {
          logger.atSevere().log("RC unexpected problem while trying to reindex local change!");
        }
        done.add(polled.indexNumber);
        logger.atFiner().log("RC finished reindexing local %s", polled.indexNumber);
        totalDone++;
      }
    }
    if (totalDone > 0) {
      logger.atFiner().log("RC finished to reindex local amount of %d out of %d", totalDone, size);
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
          logger.atSevere().log("RC INDEX_EVENT has been sent here but originator is not the right one (%s)", newEvent.getEventOrigin());
      }
    } else {
      logger.atSevere().log("RC null event has been sent here");
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
          logger.atSevere().log("fromJson method returned null for %s", newEvent.toString());
          return success;
        }

        originalEvent = new IndexToReplicateComparable(index);
      } catch (JsonSyntaxException je) {
        logger.atSevere().log("PR Could not decode json event %s", newEvent.toString(), je);
        return success;
      }
      logger.atFiner().log("RC Received this event from replication: %s", originalEvent);
      // add the data to index the change
      incomingChangeEventsToIndex.add(originalEvent);
      try {
        persister.persistIfNotAlready(originalEvent);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("RC Could not persist event %s", originalEvent);
      }
      success = true;

      synchronized (indexEventsAreReady) {
        indexEventsAreReady.notifyAll();
      }
    } catch (ClassNotFoundException e) {
      logger.atSevere().withCause(e).log("RC INDEX_EVENT has been lost. Could not find %s", newEvent.getClassName());
    }
    return success;
  }


  //We have received an event, ensuring that the wrapped event has an origin of
  //PACKFILE_EVENT, then returning the rebuilt PackFileEvent object from JSON.
  private PackFileEvent getPackFileEvent(EventWrapper event) {

    Class<?> eventClass = null;
    try {
      eventClass = Class.forName(event.getClassName());
    } catch (ClassNotFoundException e) {
      logger.atSevere().withCause(e).log("Could not get the class name from the event %s", event.toString());
    }

    return (PackFileEvent) gson.fromJson(event.getEvent(), eventClass);
  }



  // GitMS can tell Gerrit when a new packfile is available. This way Gerrit can read the packfile to cache it avoiding the
  // MissingObjectException. Gerrit will only be notified about packfiles if gitms.copy.packfile.for.gerrit is set to true.
  // This property is false by default.
  private boolean unwrapAndReadPackFile(EventWrapper event) {
    boolean success = false;

    if(event == null) {
      logger.atFiner().log("RC : Received null event");
      return false;
    }

    // Event is not null at this point so we can just check the origin.
    // Getting the PackFileEvent from JSON in the event file.
    if(event.getEventOrigin() == PACKFILE_EVENT) {
      PackFileEvent packFileEvent = getPackFileEvent(event);

      if (packFileEvent == null) {
        logger.atSevere().log("fromJson method returned null for %s", event.toString());
        return false;
      }

      File packFilePath = packFileEvent.getPackFile();
      File gitDir = new File(packFileEvent.getGitDir());

      logger.atInfo().log("RC Received packfile event from the replication. PackFile: %s, git dir: %s", packFilePath, gitDir);
      if (packFilePath.exists()) {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        final ProgressMonitor receiving = NullProgressMonitor.INSTANCE;
        final ProgressMonitor resolving = NullProgressMonitor.INSTANCE;

        Repository repo;

        try {
          // The repo must exist.
          builder.setGitDir(gitDir);
          builder.setMustExist(true);
          repo = builder.build();
        } catch (IOException e) {
          logger.atSevere().withCause(e).log("Error while initing git repo %s", gitDir);
          return false;
        }

        // Need an ObjectInserter to unpack the packfile into the repo
        try (FileInputStream fileIn = new FileInputStream(packFilePath);
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
          logger.atInfo().log("Successfully unpacked file %s", packFilePath);
        } catch (IOException e) {
          logger.atSevere().withCause(e).log("Error while unpacking");
        } finally {
          repo.close();
        }
        // Even if the packfile load was unsuccessful there is still no point to leave the packfile in the incoming directory
        // Gerrit will anyway try to rescan the git directory at a later time
        boolean deleted = packFilePath.delete();
        if (!deleted) {
          logger.atSevere().log("Cannot delete packfile %s", packFilePath);
        }
        success = true;
      } else {
        logger.atSevere().withCause(new IllegalStateException(packFilePath.getAbsolutePath())).log(
            "Packfile %s doesn't exist", packFilePath);
      }
    }
    return success;
  }


  private class IndexIncomingReplicatedEvents implements Runnable {

    @Override
    public void run() {
      logger.atInfo().log("Thread for IndexIncomingChangesThread is starting...");
      while (!finished) {
        try {
          //If a notifyAll is received from the replicator thread then
          //we will exit the synchronized block.
          synchronized (indexEventsAreReady) {
            indexEventsAreReady.wait(60 * 1000);
          }
          processIndexChangeCollection();
          // GER-638 : Taking the lock again to hold off the notifyAll in the replicator thread
          // until we are back in a waiting state. There is potential to lose events if we do not do this.
          synchronized (indexEventsAreReady) {
            processIndexChangeCollection();
          }
        } catch (Exception e) {
          logger.atSevere().withCause(e).log("RC Incoming indexing event");
        }

      }
      logger.atInfo().log("Thread for IndexIncomingChangesThread ended");
    }

    private void processIndexChangeCollection() {
      NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges = new TreeMap<>();
      IndexToReplicateComparable index;
      // make the list of changes a set of unique changes based only on the change number
      while ((index = incomingChangeEventsToIndex.poll()) != null) {
        IndexToReplicateComparable old = mapOfChanges.put(new Change.Id(index.indexNumber), index);
        if (old != null) {
          // if there are many instances for the same changeid, then delete the old ones from persistence
          persister.deleteFileIfPresentFor(old);
          logger.atInfo().log("Deleted persisted file for %s", old);
        }
      }
      if (mapOfChanges.size() > 0) {
        indexCollectionOfChanges(mapOfChanges);
      }
      reindexLocalData();
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
  private void indexCollectionOfChanges(NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges) {

    // Use trywithresources to ensure autoclosable is called on reviewdb instance.L
    try (ReviewDb db = replicatedIndexEventsManager.getReviewDbProvider().get()) {

      for (IndexToReplicateComparable i : mapOfChanges.values()) {
        if (i.delete) {
          try {
            indexer.delete(new Change.Id(i.indexNumber));
          } catch (IOException e) {
            logger.atSevere().withCause(e).log("RC Error while trying to delete change index %d", i.indexNumber);
          }
        }
      }

      logger.atFiner().log("RC Going to index %s changes...", mapOfChanges.size());

      // fetch changes from db
      ResultSet<Change> changesOnDb = db.changes().get(mapOfChanges.keySet());

      int totalDone = 0;
      int thisNodeTimeZoneOffset = getRawOffset(System.currentTimeMillis());
      logger.atFiner().log("thisNodeTimeZoneOffset=%s", thisNodeTimeZoneOffset);
      // compare changes from db with the changes landed from the index change replication
      for (Change changeOnDb : changesOnDb) {
        try {
          // If the change on the db is old (last update has been done much before the one recorded in the change,
          // the put it back in the queue
          IndexToReplicateComparable indexToReplicate = mapOfChanges.get(changeOnDb.getId());
          int landedIndexTimeZoneOffset = indexToReplicate.timeZoneRawOffset;
          logger.atFiner().log("landedIndexTimeZoneOffset=%d", landedIndexTimeZoneOffset);
          logger.atFiner().log("indexToReplicate.lastUpdatedOn.getTime() = %d", indexToReplicate.lastUpdatedOn.getTime());

          boolean changeIndexedMoreThanXMinutesAgo = changeIndexedLastTime(thisNodeTimeZoneOffset, indexToReplicate, landedIndexTimeZoneOffset);
          logger.atFiner().log("changeOnDb.getLastUpdatedOn().getTime() = %d", changeOnDb.getLastUpdatedOn().getTime());

          Timestamp normalisedChangeTimestamp = new Timestamp(changeOnDb.getLastUpdatedOn().getTime() - thisNodeTimeZoneOffset);
          Timestamp normalisedIndexToReplicate = new Timestamp(indexToReplicate.lastUpdatedOn.getTime() - landedIndexTimeZoneOffset);

          logger.atFiner().log("Comparing %s to %s. MoreThan is %s", normalisedChangeTimestamp, normalisedIndexToReplicate, changeIndexedMoreThanXMinutesAgo);
          // reindex the change if it's more than an hour it's been in the queue, or if the timestamp on the database is newer than
          // the one in the change itself
          if (normalisedChangeTimestamp.before(normalisedIndexToReplicate) && !changeIndexedMoreThanXMinutesAgo) {
            incomingChangeEventsToIndex.add(indexToReplicate);
            logger.atInfo().log("Change %s pushed back in the queue [db=%s, index=%s]", indexToReplicate.indexNumber, changeOnDb.getLastUpdatedOn(), indexToReplicate.lastUpdatedOn);
          } else {
            try {
              indexer.indexNoRepl(db, changeOnDb.getProject(), changeOnDb.getId());
              logger.atFiner().log("RC Change %s INDEXED!", changeOnDb.getChangeId());
              totalDone++;
              persister.deleteFileFor(indexToReplicate);
              logger.atFiner().log("changeOnDb.getId() = %s removed from mapOfChanges", changeOnDb.getId());
              mapOfChanges.remove(changeOnDb.getId());
            } catch (Exception e) { // could be org.eclipse.jgit.errors.MissingObjectException
              logger.atWarning().withCause(e).log("Got '%s' while trying to reindex change. Requeuing", e.getMessage());
              incomingChangeEventsToIndex.add(indexToReplicate);
              logger.atInfo().log("Change %d pushed back in the queue", indexToReplicate.indexNumber);
            }
          }
        } catch (Exception e) {
          logger.atSevere().withCause(e).log("RC Error while trying to reindex change %s", changeOnDb.getChangeId());
        }
      }

      // Check for files that have remained too long and are no longer valid
      // because they are no longer found in the database
      removeStaleIndexes(mapOfChanges);

      logger.atFiner().log("RC Finished indexing %d changes... (%d)", mapOfChanges.size(), totalDone);
    } catch (OrmException e) {
      logger.atSevere().withCause(e).log("RC Error while trying to reindex change");
    }
  }

  /**
   * Calculates the time when the change was last indexed and works
   * out whether it has been over the amount of minutes specified by the
   * value provided in the configurable (gerrit.minutes.since.last.index.check.period)
   *
   * @param thisNodeTimeZoneOffset
   * @param indexToReplicate
   * @param landedIndexTimeZoneOffset
   * @return
   */
  public boolean changeIndexedLastTime(long thisNodeTimeZoneOffset,
                                       IndexToReplicate indexToReplicate, long landedIndexTimeZoneOffset) {
    return (System.currentTimeMillis() - thisNodeTimeZoneOffset
        - (indexToReplicate.lastUpdatedOn.getTime() - landedIndexTimeZoneOffset)) >
        replicatorInstance.getMinutesSinceChangeLastIndexedCheckPeriod();
  }

  /**
   * This will remove old files in the incoming-persisted directory if they cannot be found in the DB
   * and they have existed for X amount of time
   *
   * @param mapOfChanges not found in DB
   */
  private void removeStaleIndexes(NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges) {
    // work out time zone and offSet from UTC
    TimeZone timeZone = TimeZone.getDefault();
    long offSet = timeZone.getOffset(System.currentTimeMillis());

    long currentTime = System.currentTimeMillis() - offSet;

    for (IndexToReplicateComparable current : mapOfChanges.values()) {
      if ((current.lastUpdatedOn.getTime() - current.timeZoneRawOffset) + replicatedIndexEventsManager.getIncomingPersistedLingerTime() < currentTime) {
        logger.atFiner().log("Removing stale index file %s ", current.persistFile.getName());
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

    try (ReviewDb db = replicatedIndexEventsManager.getReviewDbProvider().get()) {
      return indexSingleChange(db, indexEvent, true, forceIndexing);
    } catch (OrmException e) {
      logger.atSevere().withCause(e).log("RC Error while trying to reindex change");
    }

    return false;
  }

  /**
   * Tries to index the changes in the list. Each successful index will be recorded in the IndexOfFile itself
   *
   * @param indexToFileList
   * @return result
   */
  private int indexChanges(List<IndexToFile> indexToFileList) {
    int result = 0;

    // Use trywithresources to ensure autoclosable is called on reviewdb instance.
    try (ReviewDb db = replicatedIndexEventsManager.getReviewDbProvider().get()) {

      Set<Integer> done = new HashSet<>();

      for (IndexToFile indexToFile : indexToFileList) {
        Integer indexNumber = indexToFile.getIndexNumber();
        if (done.contains(indexNumber)) {
          logger.atFiner().log("RC Change %s has already been INDEXED!", indexNumber);
          indexToFile.successFul = true;
          result++;
        } else {
          boolean indexSuccess = indexSingleChange(db, indexToFile.indexChangeRetrier, false, false);
          if (indexSuccess) {
            result++;
            indexToFile.successFul = true;
            done.add(indexNumber);
          }
        }
      }
    } catch (OrmException e) {
      logger.atSevere().withCause(e).log("RC Error while trying to reindex change");
    }
    return result;
  }

  private boolean indexSingleChange(ReviewDb db, IndexToReplicate indexEvent, boolean enqueueIfUnsuccessful, boolean forceIndexing) throws OrmException {
    Change change = db.changes().get(new Change.Id(indexEvent.indexNumber));

    if (change == null) {
      logger.atInfo().log("RC Change %d not reindexed, not found -- deleted", indexEvent.indexNumber);
      return true;
    }

    // Each change has a timestamp which is the time when it was last modified.
    // If on the database we don't have an update-date >= timestamp, then we wait until the
    if (!forceIndexing && change.getLastUpdatedOn().before(indexEvent.lastUpdatedOn)) {
      // we need to wait until the change has been updated
      logger.atInfo().log("RC Change %d is still to be updated ****************** ", indexEvent.indexNumber);
      if (enqueueIfUnsuccessful) {
        enqueue(indexEvent);
      }
    } else {
      logger.atFiner().log("RC Change %s can be INDEXED! (%s)", indexEvent.indexNumber, forceIndexing);
      try {
        indexer.indexNoRepl(db, change.getProject(), change.getId());
        logger.atFiner().log("RC Change %s SUCCESSFULLY INDEXED!", indexEvent.indexNumber);
        return true;
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("RC Error while trying to reindex change %d", indexEvent.indexNumber);
      }
    }
    return false;
  }

  public boolean addIndexEventToUnfilteredReplicationQueue(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean deleteIndex) {

    // we only take the event if it's normal replicated daemon Gerrit functioning. If it's indexing only we ignore them
    if (dontReplicateIndexEvents) {
      return false;
    }

    for (int i = 0; i < 100; i++) {  // let's try 100 times before giving up.
      try {
        IndexToReplicate indexToReplicate = new IndexToReplicate(indexNumber, projectName, lastUpdatedOn, deleteIndex);
        unfilteredQueue.add(indexToReplicate);
        logger.atFiner().log("RC Just added %s to cache queue", indexToReplicate);
        return true;
      } catch (IllegalStateException e) {
        // The queue is full, ...
        logger.atSevere().withCause(e).log("RC error while enqueueing index event %d at i. Trying to remove some elements out of the queue...", indexNumber, i);
        try {
          // ... try to suck some events out of it
          pollAndWriteOutgoingEvents();
        } catch (InterruptedException ie) {
          logger.atWarning().withCause(ie).log("While trying to recover from full queue");
        }
      }
    }
    logger.atSevere().log("Failed to queue indexEvent with index number: %d, as queue had at least %d events.", indexNumber, unfilteredQueue.size());
    return false;
  }

  /**
   * This will write the indexEvent to disk to be handled by the thread responsible of
   * retrying to index the change again when the data is up to date in the database
   *
   * @param indexEvent
   */
  private void enqueue(IndexToReplicate indexEvent) {
    String name = String.format("I-%s-%08d.json", indexEvent.indexNumber, counter.incrementAndGet());


    File newIndexFile = new File(indexEventsDirectory, name);
    if (!checkIndexDirectory()) {
      logger.atSevere().log("RC Cannot enqueue index events, no temp directory available");
    } else {
      logger.atInfo().log("RC queueing no %d", indexEvent.indexNumber);
      try {
        File tempFile = File.createTempFile("temp", ".txt", indexEventsDirectory);

        try (FileOutputStream f = new FileOutputStream(tempFile)) {
          f.write((gson.toJson(indexEvent) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!tempFile.renameTo(newIndexFile)) {
          logger.atSevere().withCause(new IOException("RC Error while renaming!")).log(
              "RC Could not rename %s to %s", tempFile, newIndexFile);
        } else {
          logger.atFiner().log("RC Created index-event file %s", newIndexFile);
        }
      } catch (IOException e) {
        logger.atSevere().log("RC Error while storing the index event on the file system. Event will not be lost!");
      }
    }
  }

  boolean checkIndexDirectory() {
    if (!indexEventsDirectory.exists()) {
      if (!indexEventsDirectory.mkdirs()) {
        logger.atSevere().log("RC %s path cannot be created! Index events will not work!", indexEventsDirectory.getAbsolutePath());
        return false;
      }

      logger.atInfo().log("RC %s created.", indexEventsDirectory.getAbsolutePath());

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
        logger.atSevere().withCause(e).log("RC File %s is not allowed here, remove it please ", pathname);
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
    public static final long STANDARD_REINDEX_DELAY = 30 * 1000; // 30 seconds

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
      return unit.convert(STANDARD_REINDEX_DELAY - (System.currentTimeMillis() - currentTime), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      int result;
      if (o == null) {
        result = 1;
      } else {
        long diff = this.currentTime - ((IndexToReplicateDelayed) o).currentTime;
        result = diff < 0 ? -1 : diff == 0 ? (this.indexNumber - ((IndexToReplicateDelayed) o).indexNumber) : +1;
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
  public class IndexToReplicate /*implements Comparable<IndexToReplicate>*/ {
    public final int indexNumber;
    public final String projectName;
    public final Timestamp lastUpdatedOn;
    public final long currentTime;
    public static final long STANDARD_REINDEX_DELAY = 30 * 1000; // 30 seconds
    public final int timeZoneRawOffset;
    public final boolean delete;
    public long eventTimestamp;
    public String nodeIdentity;

    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
      this(indexNumber, projectName, lastUpdatedOn, System.currentTimeMillis(), getRawOffset(System.currentTimeMillis()), false);
    }

    public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean delete) {
      this(indexNumber, projectName, lastUpdatedOn, System.currentTimeMillis(), getRawOffset(System.currentTimeMillis()), delete);
    }

    protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime) {
      this(indexNumber, projectName, lastUpdatedOn, currentTime, getRawOffset(currentTime), false);
    }

    private IndexToReplicate(IndexToReplicateDelayed delayed) {
      this(delayed.indexNumber, delayed.projectName, delayed.lastUpdatedOn, delayed.currentTime, getRawOffset(delayed.currentTime), false);
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
      this.eventTimestamp = System.currentTimeMillis();
      this.nodeIdentity = replicatorInstance.getThisNodeIdentity();
    }

    @Override
    public String toString() {
      return "IndexToReplicate{" +
          "indexNumber=" + indexNumber +
          ", projectName='" + projectName + '\'' +
          ", lastUpdatedOn=" + lastUpdatedOn +
          ", currentTime=" + currentTime +
          ", timeZoneRawOffset=" + timeZoneRawOffset +
          ", delete=" + delete +
          ", eventTimestamp=" + eventTimestamp +
          ", nodeIdentity='" + nodeIdentity + '\'' +
          '}';
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
  public final class IndexToReplicateComparable extends IndexToReplicate implements Comparable<IndexToReplicate>, Persistable {
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
  public class UniqueChangesQueue implements Runnable {
    private final ConcurrentLinkedQueue<IndexToReplicate> filteredQueue = new ConcurrentLinkedQueue<>();
    private final Set<Integer> changeSet = new TreeSet<>(); // used to avoid duplicates

    public boolean add(IndexToReplicate index) {
      synchronized (changeSet) {
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

      logger.atInfo().log("RC filtered queue thread starting...");
      while (!finished) {
        try {
          int eventsGot = 0;
          synchronized (changeSet) {
            while ((indexToReplicate = filteredQueue.poll()) != null) {
              replicatorInstance.queueEventForReplication(GerritEventFactory.createReplicatedIndexEvent(indexToReplicate));
              localReindexQueue.add(IndexToReplicateDelayed.shallowCopyOf(indexToReplicate));
              changeSet.remove(indexToReplicate.indexNumber);
              eventsGot++;
            }
          }
          if (eventsGot > 0) {
            logger.atFiner().log("RC Sent %d elements from the queue", eventsGot);
          }
          // The collection (queue) of changes is effective only if many of them are collected for uniqueness.
          // So it's worth waiting in the loop to make them build up in the queue, to avoid sending duplicates around
          // If we send them right away we don't know if we are sending around duplicates.
          Thread.sleep(replicatorInstance.getReplicatedIndexUniqueChangesQueueWaitTime());
        } catch (InterruptedException ex) {
          break;
        } catch (Exception e) {
          logger.atSevere().withCause(e).log("RC Inside the queue thread");
        }
      }
      logger.atInfo().log("RC filtered queue thread finished");
    }

  }

}
