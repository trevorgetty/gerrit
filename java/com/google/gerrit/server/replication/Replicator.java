
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.server.replication;

import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.exceptions.ReplicationConfigException;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTime;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_MAX_EVENTS_PER_FILE;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME;
import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_STATS_UPDATE_TIME;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRITMS_INTERNAL_LOGGING;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_EVENT_BASEPATH;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_BASEPATH;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATION_THREAD_NAME;
import static com.google.gerrit.server.replication.ReplicationConstants.INCOMING_PERSISTED_DIR;
import static com.google.gerrit.server.replication.ReplicationConstants.INDEXING_DIR;
import static com.google.gerrit.server.replication.ReplicationConstants.REPLICATION_DISABLED;
import static com.wandisco.gerrit.gitms.shared.ReplicationConstants.EVENT_FILE_ENCODING;
import static com.wandisco.gerrit.gitms.shared.ReplicationConstants.INCOMING_DIR;
import static com.wandisco.gerrit.gitms.shared.ReplicationConstants.OUTGOING_DIR;
import static com.wandisco.gerrit.gitms.shared.ReplicationConstants.REPLICATED_EVENTS_DIR;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT;

import com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter;
import com.wandisco.gerrit.gitms.shared.exception.ConfigurationException;
import com.wandisco.gerrit.gitms.shared.events.GerritEventData;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;
import org.eclipse.jgit.lib.Config;

import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.DEFAULT_NANO;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.EVENT_FILE_NAME_FORMAT;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.EVENT_FILE_PREFIX;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.TEMPORARY_EVENT_FILE_EXTENSION;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * This is the class in charge to exchange messages with the GitMS replicator, using files.
 * So this class will call the methods in Gerrit to replicate events coming from GitMS and
 * will send events coming from Gerrit to GitMS to be replicated to the other nodes.
 * <p>
 * The main thread will poll for events in the queue and will write those events to files
 * which will be read by GitMS. Then it will read incoming files and publish those events to Gerrit.
 *
 * @author antonio
 */
public class Replicator implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final EventFileFilter incomingEventsToReplicateFileFilter =
      new EventFileFilter(EVENT_FILE_PREFIX);

  private static String eventsFileName = "";
  private static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");

  private static File replicatedEventsBaseDirectory = null;
  private static File outgoingReplEventsDirectory = null;
  private static File incomingReplEventsDirectory = null;
  private static File incomingPersistedReplEventsDirectory = null;
  private static File indexingEventsDirectory = null;

  private static String thisNodeIdentity = null;
  // as shown by statistics this means less than 2K gzipped proposals
  private static int maxNumberOfEventsBeforeProposing;

  private static long maxMillisToWaitBeforeProposingEvents;
  private static long maxMillisToWaitOnPollAndRead;
  private static long replicatedIndexUniqueChangesQueueWaitTime;
  private static long minutesSinceChangeLastIndexedCheckPeriod;
  private static long indexEventsAreReadyMillisWait;

  private static final ArrayList<String> cacheNamesNotToReload = new ArrayList<>();
  private static boolean incomingEventsAreGZipped = false; // on the landing node the text maybe already unzipped by the replicator
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static boolean syncFiles = false;
  private static String defaultBaseDir;

  // A flag, which allow there to be no application properties and for us to behave like a normal vanilla non replicated environment.
  private static Boolean replicationDisabled = null;
  private static boolean internalLogEnabled = false;

  private static GitMsApplicationProperties applicationProperties = null;
  private static final Object applicationPropertiesLocking = new Object();

  private static volatile Replicator instance = null;
  private static Object replicatorLock = new Object();

  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();

  private static Config gerritConfig = null;

  private FileOutputStream lastWriter = null;
  private String lastProjectName = null;
  private int writtenEventCount = 0;
  private File lastWriterFile = null;
  private long lastWriteTime;
  private boolean finished = false;

  //Statistics used by ShowReplicatorStats
  private static class Stats {
    private static long totalPublishedForeignEventsProsals = 0;
    private static long totalPublishedForeignEvents = 0;
    private static long totalPublishedForeignGoodEvents = 0;
    private static long totalPublishedForeignGoodEventsBytes = 0;
    private static long totalPublishedForeignEventsBytes = 0;
    private static final Multiset<Originator> totalPublishedForeignEventsByType = HashMultiset.create();

    private static long totalPublishedLocalEventsProsals = 0;
    private static long totalPublishedLocalEvents = 0;
    private static long totalPublishedLocalGoodEvents = 0;
    private static long totalPublishedLocalGoodEventsBytes = 0;
    private static long totalPublishedLocalEventsBytes = 0;
    private static final Multiset<Originator> totalPublishedLocalEventsByType = HashMultiset.create();

    private static long lastCheckedIncomingDirTime = 0;
    private static long lastCheckedOutgoingDirTime = 0;
    private static int lastIncomingDirValue = -1;
    private static int lastOutgoingDirValue = -1;
  }

  /**
   * A very core configuration override now which allows the full replication element
   * of GerritMS to be disabled and essentially for it to return to default vanilla behaviour.
   *
   * @return true if replication is DISABLED
   */
  public static boolean isReplicationDisabled() {

    if (replicationDisabled == null) {
      replicationDisabled = getOverrideBehaviour(REPLICATION_DISABLED);
    }

    return replicationDisabled;
  }

  /**
   * Handy utility method to inverse the isReplicationDisabled method, to allow it to be easily
   * supplied to implementations which expected it to be the isreplicated flag, allowing us to
   * control the turning off of replication more easily.
   *
   * @return true if replication is ENABLED
   */
  public static boolean isReplicationEnabled() {

    return !isReplicationDisabled();
  }

  public interface GerritPublishable {
    boolean publishIncomingReplicatedEvents(EventWrapper newEvent);
  }

  private final static Map<Originator, Set<GerritPublishable>> eventListeners = new HashMap<>();

  // Queue of events to replicate
  private final ConcurrentLinkedQueue<EventWrapper> queue = new ConcurrentLinkedQueue<>();

  public static synchronized Replicator getInstance(boolean create) {
    if (instance != null || create) {
      return getInstance();
    }
    return null;
  }

  public static Replicator getInstance() {

    if (instance == null) {
      synchronized (replicatorLock) {
        // using double checked locking JIC somone created while we waited on the lock!
        if (instance == null) {
          if (isReplicationDisabled()){
            logger.atInfo().log("Ignoring request for Replicator Instance - replication is disabled." );
            return null;
          }
          readConfiguration();
          logger.atInfo().log("RE Configuration read successfully");

          replicatedEventsBaseDirectory = new File(defaultBaseDir);
          outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory, OUTGOING_DIR);
          incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR);
          indexingEventsDirectory = new File(replicatedEventsBaseDirectory, INDEXING_DIR);
          incomingPersistedReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_PERSISTED_DIR);

          if (eventReaderAndPublisherThread == null) {
            instance = new Replicator();

            eventReaderAndPublisherThread = new Thread(instance);
            eventReaderAndPublisherThread.setName(GERRIT_REPLICATION_THREAD_NAME);
            eventReaderAndPublisherThread.start();
          } else {
            logger.atSevere().log("RE Thread %s is already running!", GERRIT_REPLICATION_THREAD_NAME);
            logMe("Thread " + GERRIT_REPLICATION_THREAD_NAME + " is already running!", null);
          }
        }
      }
    }
    return instance;
  }

  private Replicator() {
    logger.atFinest().log("RE Replicator constructor called...");
  }

  static void setGerritConfig(Config config) {
    gerritConfig = config;
  }

  private static synchronized void clearThread() {
    eventReaderAndPublisherThread = null;
  }

  /**
   * If the event type is in the eventListeners map already then we are
   * @param eventType
   * @return
   */
  public boolean isSubscribed(Originator eventType){
    boolean subscribed=false;
    synchronized (eventListeners) {
        if(eventListeners.containsKey(eventType)){
          subscribed = true;
        }
    }
    return subscribed;
  }

  public static void subscribeEvent(Originator eventType, GerritPublishable toCall) {
    synchronized (eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set == null) {
        set = new HashSet<>();
        eventListeners.put(eventType, set);
      }
      set.add(toCall);
      logger.atInfo().log("Subscriber added to %s", eventType);
    }
  }

  public static void unsubscribeEvent(Originator eventType, GerritPublishable toCall) {
    synchronized (eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set != null) {
        set.remove(toCall);
        logger.atInfo().log("Subscriber removed of type %s", eventType);
      }
    }
  }

  File getIndexingEventsDirectory() {
    return indexingEventsDirectory;
  }

  File getIncomingPersistedReplEventsDirectory() {
    return incomingPersistedReplEventsDirectory;
  }

  static long getMaxMillisToWaitOnPollAndRead() {
    return maxMillisToWaitOnPollAndRead;
  }

  static long getIndexEventsAreReadyMillisWait() {
    return indexEventsAreReadyMillisWait;
  }

  /**
   * This functions is just to log something when the Gerrit logger is not yet available
   * and you need to know if it's working. To be used for debugging purposes.
   * Gerrit will not log anything until the log system will be initialized.
   *
   * @param msg
   * @param t
   */
  static void logMe(String msg, Throwable t) {
    if (!internalLogEnabled) {
      return;
    }
    if (!outgoingReplEventsDirectory.exists() && !outgoingReplEventsDirectory.mkdirs()) {
      System.err.println("Cannot create directory for internal logging: " + outgoingReplEventsDirectory);
    }
    try (PrintWriter p = new PrintWriter(Files.newBufferedWriter(internalLogFile.toPath(), UTF_8, CREATE, APPEND))) {
      p.println(new Date().toString());
      p.println(msg);
      if (t != null) {
        t.printStackTrace(p);
      }
    } catch (IOException ex) {
      ex.printStackTrace(System.err);
    }
  }

  /**
   * Main thread which will poll for events in the queue, events which
   * are published by Gerrit, and will save them to files.
   * When enough (customizable) time has passed or when enough (customizable)
   * events have been saved to a file, this will be renamed with a pattern
   * that will be taken care of by the GitMS replicator, and then deleted.
   * After this or while this happens, the thread will also look for files
   * coming from the replicator, which need to be read and published as they
   * are incoming events.
   */
  @Override
  @SuppressWarnings("SleepWhileInLoop")
  public void run() {

    logger.atInfo().log("Waiting for all threads to start...");
    logMe("Waiting for all threads to start...", null);

    LifecycleManager.await();

    logger.atInfo().log("RE ReplicateEvents thread is started.");
    logMe("RE ReplicateEvents thread is started.", null);

    // we need to make this thread never fail, otherwise we'll lose events.
    while (!finished) {
      try {
        while (true) {
          // poll for the events published by gerrit and write them to disk
          boolean eventGot = pollAndWriteOutgoingEvents();
          // Look for files saved by the replicator which need to be published
          boolean published = readAndPublishIncomingEvents();
          //if one of these is true, it will not sleep.
          if (!eventGot && !published) {
            Thread.sleep(maxMillisToWaitOnPollAndRead);
          }
        }
      } catch (InterruptedException e) {
        logger.atInfo().withCause(e).log("RE Exiting");
        finished = true;
      } catch (RuntimeException e) {
        logger.atSevere().withCause(e).log("RE Unexpected exception");
        logMe("Unexpected exception", e);
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("RE Unexpected exception");
        logMe("Unexpected exception", e);
      }
    }
    logger.atInfo().log("RE Thread finished");
    logMe("Thread finished", null);
    clearThread();
    finished = true;
  }

  public void queueEventForReplication(EventWrapper event) {
    queue.offer(event); // queue is unbound, no need to check for result
  }

  public long getTotalPublishedForeignEventsProsals() {
    return Stats.totalPublishedForeignEventsProsals;
  }

  public long getTotalPublishedForeignEvents() {
    return Stats.totalPublishedForeignEvents;
  }

  public long getTotalPublishedForeignGoodEvents() {
    return Stats.totalPublishedForeignGoodEvents;
  }

  public long getTotalPublishedForeignEventsBytes() {
    return Stats.totalPublishedForeignEventsBytes;
  }

  public long getTotalPublishedForeignGoodEventsBytes() {
    return Stats.totalPublishedForeignGoodEventsBytes;
  }

  public ImmutableMultiset<Originator> getTotalPublishedForeignEventsByType() {
    return ImmutableMultiset.copyOf(Stats.totalPublishedForeignEventsByType);
  }

  public long getTotalPublishedLocalEventsProsals() {
    return Stats.totalPublishedLocalEventsProsals;
  }

  public long getTotalPublishedLocalEvents() {
    return Stats.totalPublishedLocalEvents;
  }

  public long getTotalPublishedLocalGoodEvents() {
    return Stats.totalPublishedLocalGoodEvents;
  }

  public long getTotalPublishedLocalEventsBytes() {
    return Stats.totalPublishedLocalEventsBytes;
  }

  public long getTotalPublishedLocalGoodEventsBytes() {
    return Stats.totalPublishedLocalGoodEventsBytes;
  }

  public ImmutableMultiset<Originator> getTotalPublishedLocalEventsByType() {
    return ImmutableMultiset.copyOf(Stats.totalPublishedLocalEventsByType);
  }

  public int getIncomingDirFileCount() {
    int result = -1;
    if (incomingReplEventsDirectory != null) {
      long now = System.currentTimeMillis();
      if (now - Stats.lastCheckedIncomingDirTime > DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for DEFAULT_STATS_UPDATE_TIME ms, so that continuous requests do not disturb
        File[] listFilesResult = incomingReplEventsDirectory.listFiles();
        if (listFilesResult != null) {
          Stats.lastIncomingDirValue = incomingReplEventsDirectory.listFiles().length;
          result = Stats.lastIncomingDirValue;
        }
        Stats.lastCheckedIncomingDirTime = now;
      }
    }
    return result;
  }

  public int getOutgoingDirFileCount() {
    int result = -1;
    if (outgoingReplEventsDirectory != null) {
      long now = System.currentTimeMillis();
      if (now - Stats.lastCheckedOutgoingDirTime > DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for DEFAULT_STATS_UPDATE_TIME ms, so that continuous requests do not disturb
        File[] listFilesResult = outgoingReplEventsDirectory.listFiles();
        if (listFilesResult != null) {
          Stats.lastOutgoingDirValue = outgoingReplEventsDirectory.listFiles().length;
          result = Stats.lastOutgoingDirValue;
        }
        Stats.lastCheckedOutgoingDirTime = now;
      }
    }
    return result;
  }

  /**
   * poll for the events published by gerrit and send to the other nodes through files
   * read by the replicator
   */
  private boolean pollAndWriteOutgoingEvents() {
    boolean eventGot = false;
    EventWrapper newEvent;
    while ((newEvent = queue.poll()) != null) {
      try {
        eventGot = appendToFile(newEvent);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("RE Cannot create buffer file for events queueing!");
      }
    }
    setFileReady();
    return eventGot;
  }

  /**
   * This will append to the current file the last event received.
   * If the project name of the this event is different from the the last one,
   * then we need to create a new file anyway, because we want to pack events in one
   * file only if they are for the same project.
   *
   * @param originalEvent
   * @return true if the event was successfully appended to the file
   * @throws IOException
   */
  private boolean appendToFile(final EventWrapper originalEvent) throws IOException {
    boolean result = false;

    Stats.totalPublishedLocalEvents++;
    //We have received a new event for a given project in gerrit. Creating
    //a file which is temporarily named a current-events.json if it doesn't
    //exist already and setting it as the lastWriter.
    setNewCurrentEventsFile();

    if (lastWriter != null) {
      // We are checking the projectName here in the event itself. If the project is
      // different to what was last seen then we set the existing file that was
      // written to be picked up for proposing. We do not want to write multiple events
      // to a file all for different projects.
      if (lastProjectName != null && !lastProjectName.equals(originalEvent.getProjectName())) {
        logger.atFine().log("Last project name seen [ %s ], new event project name is [ %s ]",
                             lastProjectName, originalEvent.getProjectName());
        //If the project is different, set a new current-events.json file and set the file ready
        setFileReady();
        // lastWriter will have been set to null after the renameAndReset takes
        // place as part of setFileReady. We can then set a new current-events.json
        // for events to be written
        setNewCurrentEventsFile();
      }
      //If the project is the same, write the file
      final String wrappedEvent = gson.toJson(originalEvent) + '\n';
      byte[] bytes = wrappedEvent.getBytes(EVENT_FILE_ENCODING);

      logger.atFine().log("RE Last json to be sent: %s", wrappedEvent);
      Stats.totalPublishedLocalEventsBytes += bytes.length;
      Stats.totalPublishedLocalGoodEventsBytes += bytes.length;
      Stats.totalPublishedLocalGoodEvents++;
      Stats.totalPublishedLocalEventsByType.add(originalEvent.getEventOrigin());

      writeEventsToFile(originalEvent, bytes);
      result = true;

      if (waitBeforeProposingExpired() || exceedsMaxEventsBeforeProposing())
        setFileReady();

    } else {
      throw new IOException("Internal error, null writer when attempting to append to file");
    }
    return result;
  }

  /**
   * The time of the last write to the current-events.json. If the time since the last write
   * is greater than maxSecsToWaitBeforeProposingEvents then return true
   *
   * @return
   */
  public boolean waitBeforeProposingExpired() {
    long periodOfNoWrites = System.currentTimeMillis() - lastWriteTime;
    return (periodOfNoWrites >= maxMillisToWaitBeforeProposingEvents);
  }

  /**
   * If the number of written events to the event file is greater than
   * or equal to the maxNumberOfEventsBeforeProposing then return true.
   * If we have reached the maxNumberOfEventsBeforeProposing then we must
   * propose, otherwise we can just continue to add events to the file.
   * The default value for maxNumberOfEventsBeforeProposing is 30 although this
   * is configurable by setting gerrit.replicated.events.max.append.before.proposing
   * in the application.properties.
   * @return
   */
  public boolean exceedsMaxEventsBeforeProposing() {
    return writtenEventCount >= maxNumberOfEventsBeforeProposing;
  }

  /**
   * Set the file ready by syncing with the filesystem and renaming
   */
  private void setFileReady() {
    if (writtenEventCount == 0) {
      logger.atFine().log("RE No events to send. Waiting...");
      return;
    }

    logger.atFine().log("RE Closing file and renaming to be picked up");
    try {
      lastWriter.close();
    } catch (IOException ex) {
      logger.atWarning().withCause(ex).log("RE unable to close the file to send");
    }

    if (syncFiles) {
      try {
        lastWriter.getFD().sync();
      } catch (IOException ex) {
        logger.atWarning().withCause(ex).log("RE unable to sync the file to send");
      }
    }
    renameAndReset();
  }

  /**
   * write the current-events.json file, increase the written messages count and the lastWrite time
   * lastWriteTime only set here as it is the only method doing the writing.
   *
   * @param originalEvent
   * @param bytes
   * @throws IOException
   */
  private void writeEventsToFile(final EventWrapper originalEvent, byte[] bytes) throws IOException {
    if (lastWriter != null) {
      lastWriter.write(bytes);
      lastWriteTime = System.currentTimeMillis();
      // The write has taken place so increase the writtenEventCount
      writtenEventCount++;
      logger.atFine().log("Number of events written to the events file is currently : [ %s ]", writtenEventCount);
      // Set projectName upon writing the file
      lastProjectName = originalEvent.getProjectName();
      // Here we are setting the events file name based on the last event to be written to the file.
      // In the case of multiple events being written to a single file, the events file name will take
      // the name and details of the last event to be written to it.
      setEventsFileName(originalEvent);
    }
  }

  /**
   * Parse the timestamp and nodeIdentity from which the event originated
   * and label the events file them.
   *
   * @param originalEvent
   */
  private void setEventsFileName(final EventWrapper originalEvent) throws IOException {
    //Creating a GerritEventData object from the inner event JSON of the EventWrapper object.
    GerritEventData eventData =
            ObjectUtils.createObjectFromJson(originalEvent.getEvent(), GerritEventData.class);

    if(eventData == null){
      logger.atSevere().log("Unable to set event filename, could not create "
                + "GerritEventData object from JSON %s", originalEvent.getEvent());
      return;
    }

    // If there are event types added in future that do not support the projectName member
    // then we should generate an error. The default sha1 will be used.
    if(originalEvent.getProjectName() == null){
      logger.atSevere().log("The following Event Type %s has a Null project name. "
                              + "Unable to set the event filename using the sha1 of the project name. "
                              + "Using All-Projects as the default project", originalEvent.getEvent());
    }

    String eventTimestamp = eventData.getEventTimestamp();
    // The java.lang.System.nanoTime() method returns the current value of
    // the most precise available system timer, in nanoseconds. The value returned represents
    // nanoseconds since some fixed but arbitrary time (in the future, so values may be negative)
    // and provides nanosecond precision, but not necessarily nanosecond accuracy.

    // The long value returned will be represented as a padded hexadecimal value to 16 digits in order to have a
    //guaranteed fixed length as System.nanoTime() varies in length on each OS.
    // If we are dealing with older event files where eventNanoTime doesn't exist as part of the event
    // then we will set the eventNanoTime portion to 16 zeros, same length as a nanoTime represented as HEX.
    String eventNanoTime = eventData.getEventNanoTime() != null ?
                           ObjectUtils.getHexStringOfLongObjectHash(Long.parseLong(eventData.getEventNanoTime())) : DEFAULT_NANO;
    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);
    String objectHash = ObjectUtils.getHexStringOfIntObjectHash(originalEvent.hashCode());

    //event file is now following the format
    //events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEventContents>.json

    //The EVENT_FILE_NAME_FORMAT variable is formatted with the prefix, time string, nodeId of the event and
    //a sha1 of the project name. This ensures that it will remain unique under heavy load across projects.
    //Note that a project name includes everything below the root so for example /path/subpath/repo01 is a valid project name.
    eventsFileName = String.format(EVENT_FILE_NAME_FORMAT,
        EVENT_FILE_PREFIX,
        eventTimeStr,
        eventData.getNodeIdentity(),
        getProjectNameSha1(originalEvent.getProjectName()),
        objectHash);
  }

  /**
   * Based on the project name, if the project is different, create/append a new file
   *
   * @throws FileNotFoundException
   */
  private void setNewCurrentEventsFile() throws IOException {
    if (lastWriter == null) {
      createOutgoingEventsDir();
      if (outgoingReplEventsDirectory.exists() && outgoingReplEventsDirectory.isDirectory()) {
        lastWriterFile = File.createTempFile(EVENT_FILE_PREFIX, TEMPORARY_EVENT_FILE_EXTENSION,
                                             outgoingReplEventsDirectory);
        lastWriter = new FileOutputStream(lastWriterFile, true);
        lastProjectName = null;
        writtenEventCount = 0;
      } else {
        throw new FileNotFoundException("Outgoing replicated events directory not found");
      }
    }
  }

  /**
   * Creates the outgoing replicated events directory.
   *
   * @throws FileNotFoundException
   */
  private void createOutgoingEventsDir() throws FileNotFoundException {
    if (!outgoingReplEventsDirectory.exists()) {
      boolean directoryCreated = outgoingReplEventsDirectory.mkdirs();
      if (!directoryCreated) {
        throw new FileNotFoundException("Could not create replicated events directory");
      }
      logger.atInfo().log("RE Created directory %s for replicated events",
          outgoingReplEventsDirectory.getAbsolutePath());
    }
  }

  /**
   * Rename the outgoing events_<randomnum>.tmp file to a unique filename
   * Resetting the lastWriter and count of the writtenMessageCount
   */
  private void renameAndReset() {

    //eventsFileName should not be null or empty at this point as it should have been set by
    //the setEventsFileName() method
    if(Strings.isNullOrEmpty(eventsFileName)) {
      logger.atSevere().log("RE eventsFileName was not set correctly, losing events!");
      return;
    }

    File newFile = new File(outgoingReplEventsDirectory, eventsFileName);

    //Documentation states the following for 'renameTo'
    //* Many aspects of the behavior of this method are inherently
    //* platform-dependent: The rename operation might not be able to move a
    //* file from one filesystem to another, it might not be atomic, and it
    //* might not succeed if a file with the destination abstract pathname
    //* already exists. The return value should always be checked to make sure
    //* that the rename operation was successful.
    //We should therefore consider an alternative in future.
    boolean renamed = lastWriterFile.renameTo(newFile);
    if (!renamed) {
      logger.atSevere().log("RE Could not rename file to be picked up, losing events! %s",
          lastWriterFile.getAbsolutePath());
    } else {
      logger.atFine().log("RE Created new file %s to be proposed", newFile.getAbsolutePath());
    }
    //As we are renaming the file and syncing it with the disk,
    //this means it is officially written. We then must reset all the variables
    //in order to prepare for the next event file to be written.
    lastWriter = null;
    lastWriterFile = null;
    lastWriteTime = 0;
    writtenEventCount = 0;
    lastProjectName = null;
    Stats.totalPublishedLocalEventsProsals++;
  }

  /**
   * Look for files written by the replicator in the right directory
   * and read them to publish the contained events
   *
   * @return true if something has been published
   */
  private boolean readAndPublishIncomingEvents() {
    boolean result = false;
    if (!incomingReplEventsDirectory.exists()) {
      if (!incomingReplEventsDirectory.mkdirs()) {
        logger.atSevere().log("RE %s path cannot be created! Replicated events will not work!",
                              incomingReplEventsDirectory.getAbsolutePath());
        return result;
      }

      logger.atInfo().log("RE %s created.", incomingReplEventsDirectory.getAbsolutePath());
    }
    try {
      File[] listFiles = incomingReplEventsDirectory.listFiles(incomingEventsToReplicateFileFilter);
      if (listFiles == null) {
        logger.atSevere().withCause( new IllegalStateException("RE Cannot read files"))
              .log("RE Cannot read files in directory %s. Too many files open?",
                              incomingReplEventsDirectory);
      } else if (listFiles.length > 0) {
        logger.atFine().log("RE Found %s files", listFiles.length);

        Arrays.sort(listFiles);
        for (File file : listFiles) {
          //Adding debug logging to allow for checking the sorting of events files
          logger.atFine().log("Reading incoming event file : %s", file.getName());
          try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            FileInputStream plainFileReader = new FileInputStream(file);
            InputStream reader = null;
            try {
              if (incomingEventsAreGZipped) {
                reader = new GZIPInputStream(plainFileReader);
              } else {
                reader = plainFileReader;
              }
              copyFile(reader, bos);
            } finally {
              if (reader != null) {
                reader.close(); // superfluous?
              }
            }

            int failedEvents = publishEvents(bos.toByteArray());

            // if some events failed copy the file to the failed directory
            if (failedEvents > 0) {
              logger.atSevere().log("RE There was %s failed events in this file %s, " +
                                    "moving the failed event file to the failed events directory",
                                    failedEvents, file.getAbsolutePath());
              Persister.moveFileToFailed(incomingReplEventsDirectory, file);
              //Once we move the failed events file to the failed events directory
              //then there is nothing more to do here. We should just return at this point.
              //result will be false at this point.
              return result;
            }

            //result gets set to true if we have no failed events.
            result = true;

            //We want to delete events files that have been successfully published
            //as there is no need for them to linger in the incoming directory.
            boolean deleted = file.delete();
            if (!deleted) {
              logger.atSevere().log("RE Could not delete file %s", file.getAbsolutePath());
            }
          } catch (IOException e) {
            logger.atSevere().withCause(e).log("RE while reading file %s", file.getAbsolutePath());
          }
        }
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("RE error while reading events from incoming queue");
    }
    return result;
  }

  private void copyFile(InputStream source, OutputStream dest) throws IOException {
    try (InputStream fis = source) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }

  public String getThisNodeIdentity() {
    return thisNodeIdentity;
  }

  /**
   * Recreate wrapped events and builds a list of EventData objects which
   * are used to sort upon. EventData object contain the eventTimestamp,
   * eventNanoTime of the event along with the EventWrapper object. A sort is
   * performed using a comparator which sorts on both times of the object.
   * @param eventsBytes
   * @return
   * @throws IOException
   */
  private List<EventTime> sortEvents(byte[] eventsBytes) throws IOException, InvalidEventJsonException {

    List<EventTime> eventDataList = new ArrayList<>();
    String[] events =
            new String(eventsBytes, StandardCharsets.UTF_8).split("\n");

    for (String event : events) {
      if(event == null){
        throw new InvalidEventJsonException("Event file is invalid, missing / null events.");
      }
      EventWrapper originalEvent;
      try {
        originalEvent = gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e){
        throw new InvalidEventJsonException(String.format("Event file contains Invalid JSON: \"%s\", \"%s\"",
                                                          event, e.getMessage()));
      }

      GerritEventData eventData =
              ObjectUtils.createObjectFromJson(originalEvent.getEvent(), GerritEventData.class);

      eventDataList.add(new EventTime(
              Long.parseLong(eventData.getEventTimestamp()),
              Long.parseLong(eventData.getEventNanoTime()), originalEvent));
    }

    //sort the event data list using a chained comparator.
    eventDataList.sort(new ChainedEventComparator(
            new EventTimestampComparator(),
            new EventNanoTimeComparator()));
    return eventDataList;
  }

  /**
   * From the bytes we read from disk, which the replicator provided, we
   * recreate the event using the name of the class embedded in the json text.
   * We then add the replicated flag to the object to avoid loops in sending
   * this event over and over again
   *
   * @param eventsBytes
   * @return number of failures
   */
  private int publishEvents(byte[] eventsBytes) {
    logger.atFine().log("RE Trying to publish original events...");
    Stats.totalPublishedForeignEventsBytes += eventsBytes.length;
    Stats.totalPublishedForeignEventsProsals++;
    int failedEvents = 0;

    List<EventTime> sortedEvents = null;
    try {
      sortedEvents = sortEvents(eventsBytes);
    } catch (IOException | InvalidEventJsonException e) {
      logger.atSevere().log("RE Unable to sort events as there are invalid events in the event file %s",
                e.getMessage());
      failedEvents++;
    }

    if(sortedEvents != null){
      for (EventTime event: sortedEvents) {
        Stats.totalPublishedForeignEvents++;
        EventWrapper originalEvent = event.getEventWrapper();

        if (originalEvent == null) {
          logger.atSevere().log("RE fromJson method returned null for event -> " +
                                "eventTimestamp[%d] | eventNanoTime[%d]",
                                event.getEventTimestamp(), event.getEventNanoTime());
          failedEvents++;
          continue;
        }

        if (originalEvent.getEvent().isEmpty()) {
          logger.atSevere().withCause(
                  new Exception(String.format("Internal error, event is empty -> " +
                                              "eventTimestamp[%d] | eventNanoTime[%d]",
                                              event.getEventTimestamp(), event.getEventNanoTime())))
                .log("RE event GSON string is invalid!");
          failedEvents++;
          continue;
        }

        if (originalEvent.getEvent().length() <= 2) {
          logger.atSevere().withCause(
                  new Exception(String.format("Internal error, event is invalid -> " +
                                              "eventTimestamp[%d] | eventNanoTime[%d]",
                                              event.getEventTimestamp(), event.getEventNanoTime())))
                .log("RE event GSON string is invalid!");
          failedEvents++;
          continue;
        }

        try {
          Stats.totalPublishedForeignGoodEventsBytes += eventsBytes.length;
          Stats.totalPublishedForeignGoodEvents++;
          synchronized (eventListeners) {
            Stats.totalPublishedForeignEventsByType.add(originalEvent.getEventOrigin());
            Set<GerritPublishable> clients = eventListeners.get(originalEvent.getEventOrigin());
            if (clients != null) {
              if (originalEvent.getEventOrigin() == DELETE_PROJECT_MESSAGE_EVENT) {
                continue;
              }
              for (GerritPublishable gp : clients) {
                try {
                  boolean result = gp.publishIncomingReplicatedEvents(originalEvent);

                  if (!result) {
                    failedEvents++;
                  }
                } catch (Exception e) {
                  logger.atSevere().withCause(e).log("RE While publishing events");
                  failedEvents++;
                }
              }
            }
          }
        } catch (JsonSyntaxException e) {
          logger.atSevere().withCause(e).log("RE event has been lost. Could not rebuild obj using GSON");
          failedEvents++;
        }
      }
    }

    return failedEvents;
  }

  /**
   * If in the Gerrit Configuration file the cache value for memoryLimit is 0 then
   * it means that no cache is configured and we are not going to replicate this kind of events.
   * <p>
   * Example gerrit config file:
   * [cache "accounts"]
   * memorylimit = 0
   * disklimit = 0
   * [cache "accounts_byemail"]
   * memorylimit = 0
   * disklimit = 0
   * <p>
   * There is here a small probability of race condition due to the use of the static and the global
   * gerritConfig variable. But in the worst case, we can miss just one call (because once it's initialized
   * it's stable)
   *
   * @param cacheName
   * @return true is the cache is not disabled, i.e. the name does not show up in the gerrit config file with a value of 0 memoryLimit
   */
  final static boolean isCacheToBeEvicted(String cacheName) {
    return !(gerritConfig != null && gerritConfig.getLong("cache", cacheName, "memoryLimit", 4096) == 0);
  }

  final static boolean isCacheToBeReloaded(String cacheName) {
    return !cacheNamesNotToReload.contains(cacheName);
  }

  /**
   * @return
   */
  public static GitMsApplicationProperties getApplicationProperties() {
    if (applicationProperties != null) {
      return applicationProperties;
    }

    synchronized (applicationPropertiesLocking) {
      if (applicationProperties != null) {
        return applicationProperties;
      }

      // N.B. This is for DEBUG use only.
      // Allow for the gitms system to be disabled, via an override java property or system environment value.
      // Allow the running of vanilla non replicated integration tests, and other simple scenarios.
      internalLogEnabled = getOverrideBehaviour(GERRITMS_INTERNAL_LOGGING);

      if (internalLogEnabled) {
        internalLogFile = new File(new File(DEFAULT_BASE_DIR), "replEvents.log"); // used for debug
      }

      try {
        applicationProperties = new GitMsApplicationProperties();
        return applicationProperties;
      } catch (final IOException | ConfigurationException ex) {
        throw new ReplicationConfigException("Unable to initialise application properties", ex);
      }
    }
  }

  public static boolean readConfiguration() {
    GitMsApplicationProperties props = getApplicationProperties();

    syncFiles = props.getPropertyAsBoolean(GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES, "false");

    // The user can set a different path specific for the replicated events. If it's not there
    // then the usual GERRIT_EVENT_BASEPATH will be taken.
    defaultBaseDir = props.getProperty(GERRIT_REPLICATED_EVENTS_BASEPATH);
    if (defaultBaseDir == null) {
      defaultBaseDir = props.getProperty(GERRIT_EVENT_BASEPATH);
      if (defaultBaseDir == null) {
        defaultBaseDir = DEFAULT_BASE_DIR;
      }
      defaultBaseDir += File.separator + REPLICATED_EVENTS_DIR;
    }

    incomingEventsAreGZipped = props.getPropertyAsBoolean(GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED, "false");

    //Getting the node identity that will be used to determine the originating node for each instance.
    thisNodeIdentity = props.getProperty("node.id");

    //Configurable for the maximum amount of events allowed in the outgoing events file before proposing.
    maxNumberOfEventsBeforeProposing = Integer.parseInt(
        cleanLforLong(props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, DEFAULT_MAX_EVENTS_PER_FILE)));

    //Configurable for the maximum amount of seconds to wait before proposing events in the outgoing events file.
    maxMillisToWaitBeforeProposingEvents = Long.parseLong(
        cleanLforLongAndConvertToMilliseconds(props.getProperty(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
            DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));

    //Configurable for the wait time for threads waiting on an event to be received and published.
    maxMillisToWaitOnPollAndRead = Long.parseLong(
        cleanLforLongAndConvertToMilliseconds(props.getProperty(GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ,
            DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ)));

    //Configurable for changing the wait time on building up the unique replicated index events in the unique changes queue.
    replicatedIndexUniqueChangesQueueWaitTime = Long.parseLong(
        cleanLforLongAndConvertToMilliseconds(props.getProperty(GERRIT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME,
            DEFAULT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME)));

    //Configurable for the time period to check since the change was last indexed, The change will need reindexed
    //if it has been in the queue more than the specified check period. Default is 1 hour.
    minutesSinceChangeLastIndexedCheckPeriod = TimeUnit.MINUTES.toMillis(Long.parseLong(
        props.getProperty(GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD, DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD)));

    //Tunable for wait in ReplicatedIndexEventsManager for synchronized block
    //i.e, instance.indexEventsAreReady.wait(60*1000) is now instance.indexEventsAreReady.wait(Replicator.indexEventsAreReadySecondsWait)
    //Default is 60 seconds
    indexEventsAreReadyMillisWait = Long.parseLong(
        cleanLforLongAndConvertToMilliseconds(props.getProperty(GERRIT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME,
            DEFAULT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME)));

    logger.atInfo().log("Property %s=%s", GERRIT_REPLICATED_EVENTS_BASEPATH, defaultBaseDir);

    // Replicated CACHE properties
    try {
      String[] tempCacheNames = props.getProperty(GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED, "invalid_cache_name").split(",");
      for (String s : tempCacheNames) {
        String st = s.trim();
        if (st.length() > 0) {
          cacheNamesNotToReload.add(st);
        }
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Not able to load cache properties");
    }

    return true;
  }

  /**
   * Utility method to get the system override properties and returns them as a boolean
   * indicating whether they are enabled / disabled.
   *
   * @param overrideName
   * @return
   */
  private static boolean getOverrideBehaviour(String overrideName) {

    // work out system env value first... Note as env is case sensitive and properties usually lower case, we will
    // use what the client has passed in, but also request toUpper for the environment option JIC.
    // e.g. 'replication_disabled' the property would be 'REPLICATION_DISABLED' the environment var.
    String env = System.getenv(overrideName);
    if ( Strings.isNullOrEmpty(env)){
      // retry with uppercase
      env = System.getenv(overrideName.toUpperCase());
    }
    return Boolean.parseBoolean(System.getProperty(overrideName, env));
  }

  /**
   * Configurable wait time to build up unique changes for the replicated index queue.
   *
   * @return
   */
  public long getReplicatedIndexUniqueChangesQueueWaitTime() {
    return replicatedIndexUniqueChangesQueueWaitTime;
  }

  /**
   * Returns the number of minutes since the change was last indexed
   *
   * @return
   */
  public static long getMinutesSinceChangeLastIndexedCheckPeriod() {
    return minutesSinceChangeLastIndexedCheckPeriod;
  }

  private static String cleanLforLong(String property) {
    if (property != null && property.length() > 1 && (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0, property.length() - 1);
    }
    return property;
  }

  /**
   * Using milliseconds so that the user can specify sub second
   * periods
   *
   * @param property the string value taken from the properties file
   * @return the string value in milliseconds
   */
  public static String cleanLforLongAndConvertToMilliseconds(String property) {
    if (property != null && property.length() > 1 && (property.endsWith("L") || property.endsWith("l"))) {
      property = property.substring(0, property.length() - 1);
    }

    // Convert to milliseconds
    if (property.contains(".")) {
      double x = Double.parseDouble(property) * 1000;
      int y = (int) x;
      property = Integer.toString(y);
    } else {
      int x = Integer.parseInt(property) * 1000;
      property = Integer.toString(x);
    }

    return property;
  }
}
