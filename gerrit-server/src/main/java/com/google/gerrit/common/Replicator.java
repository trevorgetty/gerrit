
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

import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.GsonBuilder;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTime;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.GerritEventData;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.jgit.util.FS;

/**
 * This is the class in charge to exchange messages with the GitMS replicator, using files.
 * So this class will call the methods in Gerrit to replicate events coming from GitMS and
 * will send events coming from Gerrit to GitMS to be replicated to the other nodes.
 *
 * The main thread will poll for events in the queue and will write those events to files
 * which will be read by GitMS. Then it will read incoming files and publish those events to Gerrit.
 *
 * @author antonio
 */
public class Replicator implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(Replicator.class);

  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES = "gerrit.replicated.events.enabled.sync.files";
  public static final String GERRIT_REPLICATED_EVENTS_BASEPATH = "gerrit.replicated.events.basepath";
  public static final String GERRIT_EVENT_BASEPATH = "gerrit.events.basepath";

  public static final String GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED = "gerrit.replicated.events.incoming.gzipped";
  public static final String GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "gerrit.replicated.events.secs.before.proposing";
  public static final String GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED = "gerrit.replicated.cache.names.not.to.reload";
  public static final String GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING = "gerrit.replicated.events.max.append.before.proposing";
  public static final String GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "gerrit.max.secs.to.wait.on.poll.and.read";
  public static final String GERRIT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME = "gerrit.replicated.index.unique.changes.queue.wait.time";
  public static final String GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "gerrit.minutes.since.change.last.indexed.check.period";
  public static final String ENC = "UTF-8"; // From BaseCommand

  // as shown by statistics this means less than 2K gzipped proposals
  public static final String DEFAULT_MAX_EVENTS_PER_FILE = "30";

  //Default wait times if no configuration provided in application.properties
  public static final String DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "5";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "1";
  public static final String DEFAULT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME = "20";
  public static final String DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "60";

  private static final String FIRST_PART="events";
  public static final String DEFAULT_NANO = "0000000000000000";
  //event file is now following the format
  //events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEvent>.json
  public static final String NEXT_EVENTS_FILE = FIRST_PART+"_%s_%s_%s_%s.json";
  public static String eventsFileName = "";

  public static final String GERRIT_REPLICATION_THREAD_NAME = "ReplicatorStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES = "/opt/wandisco/git-multisite/replicator/properties/";
  public static final String REPLICATED_EVENTS_DIRECTORY_NAME = "replicated_events";
  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");
  public static final String OUTGOING_DIR = "outgoing";
  public static final String INCOMING_DIR = "incoming";
  public static final String INDEXING_DIR = "index_events";
  public static final String INCOMING_PERSISTED_DIR = "incoming-persisted";
  public static final boolean internalLogEnabled = false;

  private static File replicatedEventsBaseDirectory = null;
  private static File outgoingReplEventsDirectory = null;
  private static File incomingReplEventsDirectory = null;
  private static File incomingPersistedReplEventsDirectory = null;
  private static File indexingEventsDirectory = null;

  private static String thisNodeIdentity = null;
  // as shown by statistics this means less than 2K gzipped proposals
  private static int maxNumberOfEventsBeforeProposing;

  //Wait time variables in milliSeconds
  private static long maxSecsToWaitBeforeProposingEvents;
  public static long maxSecsToWaitOnPollAndRead;
  public static long replicatedIndexUniqueChangesQueueWaitTime;
  public static long minutesSinceChangeLastIndexedCheckPeriod;

  private static final ArrayList<String> cacheNamesNotToReload = new ArrayList<>();
  private static final ReplicatedEventsFileFilter incomingEventsToReplicateFileFilter = new ReplicatedEventsFileFilter(FIRST_PART);
  private static boolean incomingEventsAreGZipped = false; // on the landing node the text maybe already unzipped by the replicator
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static boolean syncFiles = false;
  private static String defaultBaseDir;
  private static volatile Replicator instance = null;
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
  public static class Stats {
    private static long totalPublishedForeignEventsProsals = 0;
    private static long totalPublishedForeignEvents = 0;
    private static long totalPublishedForeignGoodEvents = 0;
    private static long totalPublishedForeignGoodEventsBytes = 0;
    private static long totalPublishedForeignEventsBytes = 0;
    private static final Multiset<EventWrapper.Originator> totalPublishedForeignEventsByType = HashMultiset.create();

    private static long totalPublishedLocalEventsProsals = 0;
    private static long totalPublishedLocalEvents = 0;
    private static long totalPublishedLocalGoodEvents = 0;
    private static long totalPublishedLocalGoodEventsBytes = 0;
    private static long totalPublishedLocalEventsBytes = 0;
    private static final Multiset<EventWrapper.Originator> totalPublishedLocalEventsByType = HashMultiset.create();

    private static long lastCheckedIncomingDirTime = 0;
    private static long lastCheckedOutgoingDirTime = 0;
    private static int lastIncomingDirValue = -1;
    private static int lastOutgoingDirValue = -1;
    public static long DEFAULT_STATS_UPDATE_TIME = 20000L;
  }


  public interface GerritPublishable {
      boolean publishIncomingReplicatedEvents(EventWrapper newEvent);
  }

  private final static Map<EventWrapper.Originator,Set<GerritPublishable>> eventListeners = new HashMap<>();

  // Queue of events to replicate
  private final ConcurrentLinkedQueue<EventWrapper> queue = new ConcurrentLinkedQueue<>();

  public static synchronized Replicator getInstance(boolean create) {
    if (instance != null || create) {
      return getInstance();
    }
    return null;
  }

  public static Replicator getInstance() {

    if (internalLogEnabled) {
      internalLogFile = new File(new File(DEFAULT_BASE_DIR),"replEvents.log"); // used for debug
    }
    if (instance == null) {
      synchronized (gson) {
        if (instance == null) {
          boolean configOk = readConfiguration();
          log.info("RE Configuration read: ok? {}",configOk);
          replicatedEventsBaseDirectory = new File(defaultBaseDir);
          outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory,OUTGOING_DIR);
          incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory,INCOMING_DIR);
          indexingEventsDirectory = new File(replicatedEventsBaseDirectory,INDEXING_DIR);
          incomingPersistedReplEventsDirectory = new File(replicatedEventsBaseDirectory,INCOMING_PERSISTED_DIR);

          if (eventReaderAndPublisherThread == null) {
            instance = new Replicator();

            eventReaderAndPublisherThread = new Thread(instance);
            eventReaderAndPublisherThread.setName(GERRIT_REPLICATION_THREAD_NAME);
            eventReaderAndPublisherThread.start();
          } else {
            log.error("RE Thread {} is already running!",GERRIT_REPLICATION_THREAD_NAME);
            logMe("Thread " + GERRIT_REPLICATION_THREAD_NAME + " is already running!",null);
          }
        }
      }
    }
    return instance;
  }

  private Replicator() {
    log.debug("RE Replicator constructor called...");
  }

  static void setGerritConfig(Config config) {
    gerritConfig = config;
  }

  private static synchronized void clearThread() {
    eventReaderAndPublisherThread = null;
  }

  public static void subscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
    synchronized (eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set == null) {
        set = new HashSet<>();
        eventListeners.put(eventType, set);
      }
      set.add(toCall);
      log.info("Subscriber added to {}",eventType);
    }
  }

  public static void unsubscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
    synchronized (eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set != null) {
        set.remove(toCall);
        log.info("Subscriber removed of type {}",eventType);
      }
    }
  }

  public File getIndexingEventsDirectory() {
    return indexingEventsDirectory;
  }

  public File getIncomingPersistedReplEventsDirectory() {
    return incomingPersistedReplEventsDirectory;
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
    try (PrintWriter p = new PrintWriter(new FileWriter(internalLogFile, true))) {
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

    log.info("Waiting for all threads to start...");
    logMe("Waiting for all threads to start...", null);

    LifecycleManager.await();

    log.info("RE ReplicateEvents thread is started.");
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
            Thread.sleep(maxSecsToWaitOnPollAndRead);
          }
        }
      } catch (InterruptedException e) {
        log.info("RE Exiting", e);
        finished = true;
      } catch (RuntimeException e) {
        log.error("RE Unexpected exception", e);
        logMe("Unexpected exception", e);
      } catch (Exception e) {
        log.error("RE Unexpected exception", e);
        logMe("Unexpected exception", e);
      }
    }
    log.error("RE Thread finished");
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

  public ImmutableMultiset<EventWrapper.Originator> getTotalPublishedForeignEventsByType() {
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

  public ImmutableMultiset<EventWrapper.Originator> getTotalPublishedLocalEventsByType() {
    return ImmutableMultiset.copyOf(Stats.totalPublishedLocalEventsByType);
  }

  public int getIncomingDirFileCount() {
    int result = -1;
    if (incomingReplEventsDirectory != null) {
      long now = System.currentTimeMillis();
      if (now - Stats.lastCheckedIncomingDirTime > Stats.DEFAULT_STATS_UPDATE_TIME) {
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
      if (now - Stats.lastCheckedOutgoingDirTime > Stats.DEFAULT_STATS_UPDATE_TIME) {
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
        log.error("RE Cannot create buffer file for events queueing!", e);
      }
    }
    setFileReady();
    return eventGot;
  }

  /**
   * This will create append to the current file the last event received.
   * If the project name of the this event is different from the the last one,
   * then we need to create a new file anyway, because we want to pack events in one
   * file only if the are for the same project
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

      //* We are checking the projectName here in the event itself. If the project is
      //* different to what was last seen then we set the existing file that was
      //* written to be picked up for proposing. We do not want to write multiple events
      //* to a file all for different projects.
      if (lastProjectName != null && !lastProjectName.equals(originalEvent.getProjectName())) {
        log.debug("Last project name seen [ "+lastProjectName+" ], new event project name is [ "+originalEvent.getProjectName()+" ]");
        setFileReady();
        //* lastWriter will have been set to null after the renameAndReset takes
        //* place as part of setFileReady. We can then set a new current-events.json
        //* for events to be written
        setNewCurrentEventsFile();
      }
      //If the project is the same, write the file
      final String wrappedEvent = gson.toJson(originalEvent) + '\n';
      byte[] bytes = wrappedEvent.getBytes(ENC);

      log.debug("RE Last json to be sent: {}", wrappedEvent);
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
    return (periodOfNoWrites >= maxSecsToWaitBeforeProposingEvents);
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
      log.debug("RE No events to send. Waiting...");
      return;
    }

    log.debug("RE Closing file and renaming to be picked up");
    try {
      lastWriter.close();
    } catch (IOException ex) {
      log.warn("RE unable to close the file to send", ex);
    }

    if (syncFiles) {
      try {
        lastWriter.getFD().sync();
      } catch (IOException ex) {
        log.warn("RE unable to sync the file to send", ex);
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
      //The write has taken place so increase the writtenEventCount
      writtenEventCount++;
      log.debug("Number of events written to the events file is currently : [ " + writtenEventCount
          + " ]");
      //Set projectName upon writing the file
      lastProjectName = originalEvent.getProjectName();
      //Here we are setting the events file name based on the last event to be written to the file.
      //In the case of multiple events being written to a single file, the events file name will take
      //the name and details of the last event to be written to it.
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
      log.error("Unable to set event filename, could not create "
          + "GerritEventData object from JSON {}", originalEvent.getEvent());
      return;
    }

    //If there are event types added in future that do not support the projectName member
    //then we should generate an error. The default sha1 will be used.
    if(originalEvent.getProjectName() == null){
      log.error(String.format("The following Event Type %s has a Null project name. "
          + "Unable to set the event filename using the sha1 of the project name. "
          + "Using All-Projects as the default project", originalEvent.getEvent()));
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

    //The NEXT_EVENTS_FILE variable is formatted with the timestamp and nodeId of the event and
    //a sha1 of the project name. This ensures that it will remain unique under heavy load across projects.
    //Note that a project name includes everything below the root so for example /path/subpath/repo01 is a valid project name.
    eventsFileName = String.format(NEXT_EVENTS_FILE, eventTimeStr, eventData.getNodeIdentity(),
        getProjectNameSha1(originalEvent.getProjectName()), objectHash);

  }

  /**
   * Based on the project name, if the project is different, create/append a new file
   * @throws FileNotFoundException
   */
  private void setNewCurrentEventsFile() throws IOException {
    if(lastWriter == null) {
      createOutgoingEventsDir();
      if (outgoingReplEventsDirectory.exists() && outgoingReplEventsDirectory.isDirectory()) {
        lastWriterFile = File.createTempFile("events-", ".tmp", outgoingReplEventsDirectory);
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
   * @throws FileNotFoundException
   */
  private void createOutgoingEventsDir() throws FileNotFoundException{
    if (!outgoingReplEventsDirectory.exists()) {
      boolean directoryCreated = outgoingReplEventsDirectory.mkdirs();
      if (!directoryCreated) {
        throw new FileNotFoundException("Could not create replicated events directory");
      }
      log.info("RE Created directory {} for replicated events",
          outgoingReplEventsDirectory.getAbsolutePath());
    }
  }

  /**
   * Rename the outgoing events-<randomnum>.tmp file to a unique filename
   * Resetting the lastWriter and count of the writtenMessageCount
   */
  private void renameAndReset() {

    //eventsFileName should not be null or empty at this point as it should have been set by
    //the setEventsFileName() method
    if(Strings.isNullOrEmpty(eventsFileName)) {
      log.error("RE eventsFileName was not set correctly, losing events!");
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
      log.error("RE Could not rename file to be picked up, losing events! {}",
          lastWriterFile.getAbsolutePath());
    }

    //The rename was successful
    log.info("RE Created new file {} to be proposed", newFile.getAbsolutePath());

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
        log.error("RE {} path cannot be created! Replicated events will not work!",
            incomingReplEventsDirectory.getAbsolutePath());
        return result;
      }

      log.info("RE {} created.",incomingReplEventsDirectory.getAbsolutePath());
    }
    try {
      File[] listFiles = incomingReplEventsDirectory.listFiles(incomingEventsToReplicateFileFilter);
      if (listFiles == null) {
        log.error("RE Cannot read files in directory {}. Too many files open?",
            incomingReplEventsDirectory,new IllegalStateException("RE Cannot read files"));
      } else if (listFiles.length > 0) {
        log.debug("RE Found {} files",listFiles.length);

        Arrays.sort(listFiles);
        for (File file : listFiles) {
          //Adding debug logging to allow for checking the sorting of events files
          log.debug("Reading incoming event file : " + file.getName());
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
              copyFile(reader,bos);
            } finally {
              if (reader != null) {
                reader.close(); // superfluos?
              }
            }

            int failedEvents = publishEvents(bos.toByteArray());

            // if some events failed copy the file to the failed directory
            if (failedEvents > 0) {
              log.error("RE There was {} failed events in this file {}, "
                  + "moving the failed event file to the failed events directory", failedEvents, file.getAbsolutePath());
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
              log.error("RE Could not delete file {}",file.getAbsolutePath());
            }
          } catch (IOException e) {
            log.error("RE while reading file {}",file.getAbsolutePath(), e);
          }
        }
      }
    } catch (Exception e) {
      log.error("RE error while reading events from incoming queue", e);
    }
    return result;
  }

  private void copyFile(InputStream source, OutputStream dest) throws IOException {
    try (InputStream fis = source) {
      byte[] buf= new byte[8192];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }

  public String getThisNodeIdentity(){
    return thisNodeIdentity;
  }


  /**
   * Method recreate wrapped events and builds a list of EventData objects which
   * are used to sort upon. EventData object contain the eventTimestamp,
   * eventNanoTime of the event along with the EventWrapper object. A sort is
   * performed using a comparator which sorts on both times of the object.
   * @param eventsBytes
   * @return
   * @throws IOException
   */
  private List<EventTime> sortEvents(byte[] eventsBytes)
      throws IOException, InvalidEventJsonException {

    List<EventTime> eventDataList = new ArrayList<>();
    String[] events =
        new String(eventsBytes,StandardCharsets.UTF_8).split("\n");

    for (String event : events) {

     if(event == null){
        throw new InvalidEventJsonException("Event file is invalid, missing / null events.");
      }

      EventWrapper originalEvent;
      try {
        originalEvent = gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e){
        throw new InvalidEventJsonException(String.format("Event file contains Invalid JSON. \"%s\", \"%s\"",
            event, e.getMessage()));
      }

      GerritEventData eventData =
          ObjectUtils.createObjectFromJson(originalEvent.getEvent(), GerritEventData.class);

      eventDataList.add(new EventTime(
          Long.parseLong(eventData.getEventTimestamp()),
          Long.parseLong(eventData.getEventNanoTime()), originalEvent));
    }

    //sort the event data list using a chained comparator.
    Collections.sort(eventDataList, new ChainedEventComparator(
        new EventTimestampComparator(),
        new EventNanoTimeComparator()));
    return eventDataList;
  }

  /**
   * From the bytes we read from disk, which the replicator provided, we
   * recreate the event using the name of the class embedded in the json text.
   * We then add the replicated flag to the object to avoid loops in sending
   * this event over and over again
   * @param eventsBytes
   */
  private int publishEvents(byte[] eventsBytes) {
    log.debug("RE Trying to publish original events...");
    Stats.totalPublishedForeignEventsBytes += eventsBytes.length;
    Stats.totalPublishedForeignEventsProsals++;
    int failedEvents = 0;

    List<EventTime> sortedEvents = null;
    try {
      sortedEvents = sortEvents(eventsBytes);
    } catch (IOException | InvalidEventJsonException e) {
      log.error("RE Unable to sort events as there are invalid events in the event file {}",
          e.getMessage());
      failedEvents++;
    }

    if(sortedEvents != null){
      for (EventTime event: sortedEvents) {
        Stats.totalPublishedForeignEvents++;
        EventWrapper originalEvent = event.getEventWrapper();

        if (originalEvent == null) {
          log.error("RE fromJson method returned null for {}", event.toString());
          failedEvents++;
          continue;
        }

        if (originalEvent.getEvent().length() > 2) {
          try {
            Stats.totalPublishedForeignGoodEventsBytes += eventsBytes.length;
            Stats.totalPublishedForeignGoodEvents++;
            synchronized (eventListeners) {
              Stats.totalPublishedForeignEventsByType.add(originalEvent.getEventOrigin());
              Set<GerritPublishable> clients = eventListeners.get(originalEvent.getEventOrigin());
              if (clients != null) {
                if (originalEvent.getEventOrigin() == EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT) {
                  continue;
                }
                for (GerritPublishable gp : clients) {
                  try {
                    boolean result = gp.publishIncomingReplicatedEvents(originalEvent);

                    if (!result) {
                      failedEvents++;
                    }
                  } catch (Exception e) {
                    log.error("RE While publishing events", e);
                    failedEvents++;
                  }
                }
              }
            }
          } catch (JsonSyntaxException e) {
            log.error("RE event has been lost. Could not rebuild obj using GSON", e);
            failedEvents++;
          }
        } else {
          log.error("RE event GSON string is empty!",
              new Exception("Internal error, event is empty: " + event));
        }
      }
    }
    return failedEvents;
  }


  /**
   * If in the Gerrit Configuration file the cache value for memoryLimit is 0 then
   * it means that no cache is configured and we are not going to replicate this kind of events.
   *
   * Example gerrit config file:
   * [cache "accounts"]
        memorylimit = 0
        disklimit = 0
     [cache "accounts_byemail"]
        memorylimit = 0
        disklimit = 0
   *
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

  final static boolean isCacheToBeReloaded(String cacheName)  {
    return !cacheNamesNotToReload.contains(cacheName);
  }

  private static boolean readConfiguration() {
    boolean result = false;
    try {
      // Used for internal integration tests at WANdisco
      String gitConfigLoc = System.getProperty("GIT_CONFIG", System.getenv("GIT_CONFIG"));
      if ( Strings.isNullOrEmpty(gitConfigLoc) && System.getenv("GIT_CONFIG") == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }

      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
      try {
        config.load();
      } catch (ConfigInvalidException e) {
        // Configuration file is not in the valid format, throw exception back.
        throw new IOException(e);
      }

      File applicationProperties;
      try {
        String appProperties = config.getString("core", null, "gitmsconfig");
        applicationProperties = new File(appProperties);
        // GER-662 NPE thrown if GerritMS is started without a reference to a valid GitMS application.properties file.
      } catch (NullPointerException exception) {
        throw new FileNotFoundException("GerritMS cannot continue without a valid GitMS application.properties file referenced in its .gitconfig file.");
      }

      if(!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (1) " + applicationProperties);
        applicationProperties = new File(DEFAULT_MS_APPLICATION_PROPERTIES,"application.properties");
      }

      if(!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (2) " + applicationProperties);
        defaultBaseDir = DEFAULT_BASE_DIR+File.separator+REPLICATED_EVENTS_DIRECTORY_NAME;
      } else {
        Properties props = new Properties();
        try (FileInputStream propsFile = new FileInputStream(applicationProperties)) {
          props.load(propsFile);
          syncFiles = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES, "false"));

          // The user can set a different path specific for the replicated events. If it's not there
          // then the usual GERRIT_EVENT_BASEPATH will be taken.
          defaultBaseDir = props.getProperty(GERRIT_REPLICATED_EVENTS_BASEPATH);
          if (defaultBaseDir == null) {
            defaultBaseDir = props.getProperty(GERRIT_EVENT_BASEPATH);
            if (defaultBaseDir == null) {
              defaultBaseDir = DEFAULT_BASE_DIR;
            }
            defaultBaseDir+=File.separator+REPLICATED_EVENTS_DIRECTORY_NAME;
          }

          incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED,"false"));

          //Getting the node identity that will be used to determine the originating node for each instance.
          thisNodeIdentity = props.getProperty("node.id");

          //Configurable for the maximum amount of events allowed in the outgoing events file before proposing.
          maxNumberOfEventsBeforeProposing = Integer.parseInt(
              cleanLforLong(props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING,DEFAULT_MAX_EVENTS_PER_FILE)));

          //Configurable for the maximum amount of seconds to wait before proposing events in the outgoing events file.
          maxSecsToWaitBeforeProposingEvents = Long.parseLong(
              cleanLforLongAndConvertToMilliseconds(props.getProperty(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
                  DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));

          //Configurable for the wait time for threads waiting on an event to be received and published.
          maxSecsToWaitOnPollAndRead = Long.parseLong(
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


          log.info("Property {}={}",new Object[] {GERRIT_REPLICATED_EVENTS_BASEPATH,defaultBaseDir});

          // Replicated CACHE properties
          try {
            String[] tempCacheNames= props.getProperty(GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED,"invalid_cache_name").split(",");
            for (String s: tempCacheNames) {
              String st = s.trim();
              if (st.length() > 0) {
                cacheNamesNotToReload.add(st);
              }
            }
          } catch(Exception e) {
            log.error("Not able to load cache properties",e);
          }
          result = true;
        } catch(IOException e) {
          log.error("While reading GerritMS properties file",e);
        }
      }
    } catch(IOException ee) {
      log.error("While loading the .gitconfig file",ee);
    }
    return result;
  }

  /**
   * Configurable wait time to build up unique changes for the replicated index queue.
   * @return
   */
  public long getReplicatedIndexUniqueChangesQueueWaitTime(){
    return replicatedIndexUniqueChangesQueueWaitTime;
  }

  /**
   * Returns the number of minutes since the change was last indexed
   * @return
   */
  public static long getMinutesSinceChangeLastIndexedCheckPeriod() {
    return minutesSinceChangeLastIndexedCheckPeriod;
  }

  private static String cleanLforLong(String property) {
    if (property != null && property.length() > 1 &&  (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0,property.length()-1);
    }
    return property;
  }

 /**
  *  Using milliseconds so that the user can specify sub second
  *  periods
  *
  * @param property the string value taken from the properties file
  * @return the string value in milliseconds
  */
  public static String cleanLforLongAndConvertToMilliseconds(String property) {
    if (property != null && property.length() > 1 &&  (property.endsWith("L") || property.endsWith("l"))) {
      property = property.substring(0,property.length()-1);
    }

    // Convert to milliseconds
    if (property.contains(".")){
      double x = Double.parseDouble(property)*1000;
      int y = (int) x;
      property = Integer.toString(y);
    } else {
      int x = Integer.parseInt(property)*1000;
      property = Integer.toString(x);
    }

    return property;
  }
}
