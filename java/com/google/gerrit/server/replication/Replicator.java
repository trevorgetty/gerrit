
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
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import static com.google.gerrit.server.replication.ReplicationConstants.*;
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

  public static final String ENC = "UTF-8"; // From BaseCommand

  public static final String CURRENT_EVENTS_FILE = "current-events.json";
  public static final String NEXT_EVENTS_FILE = "events-%s-%s-%02d.json";
  public static String eventsFileName = "";
  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");

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
  private static final IncomingEventsToReplicateFileFilter incomingEventsToReplicateFileFilter = new IncomingEventsToReplicateFileFilter();
  private static boolean incomingEventsAreGZipped = false; // on the landing node the text maybe already unzipped by the replicator
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static boolean syncFiles = false;
  private static String defaultBaseDir;

  // A flag, which allow there to be no application properties and for us to behave like a normal vanilla non replicated environment.
  private static Boolean replicationDisabled = null;
  public static boolean internalLogEnabled = false;
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
  private int writtenMessageCount = 0;
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

  /**
   * A Very core configuration override now which allows the full replication element
   * of GerritMS to be disabled and essentially for it to return to default vanilla behaviour.
   *
   * @return true if replication is DISABLED
   */
  public static boolean isReplicationDisabled() {

    if ( replicationDisabled == null ){
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

  private final static Map<EventWrapper.Originator, Set<GerritPublishable>> eventListeners = new HashMap<>();

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
          boolean configOk = readConfiguration();
          logger.atInfo().log("RE Configuration read: ok? %s", configOk);

          if ( configOk == false )
          {
            // either there was an issue, or we have had GitMS Disabled, either way we should not continue
            // with any more replication thread enablement - allows vanilla testing with this war.
            return null;
          }

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

  public static void subscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
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

  public static void unsubscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
    synchronized (eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set != null) {
        set.remove(toCall);
        logger.atInfo().log("Subscriber removed of type %s", eventType);
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
            Thread.sleep(maxSecsToWaitOnPollAndRead);
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
        logger.atSevere().withCause(e).log("RE Cannot create buffer file for events queueing!");
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
    setCurrentEventsFile();

    if (lastWriter != null) {
      if (lastProjectName != null && !lastProjectName.equals(originalEvent.projectName)) {
        //If the project is different, set a new current-events.json file and set the file ready
        setFileReady();
        setCurrentEventsFile();
      }
      //If the project is the same, write the file
      final String msg = gson.toJson(originalEvent) + '\n';
      byte[] bytes = msg.getBytes(ENC);

      logger.atFiner().log("RE Last json to be sent: %s", msg);
      Stats.totalPublishedLocalEventsBytes += bytes.length;
      Stats.totalPublishedLocalGoodEventsBytes += bytes.length;
      Stats.totalPublishedLocalGoodEvents++;
      Stats.totalPublishedLocalEventsByType.add(originalEvent.originator);

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
   * If the number of writtenMessageCount exceeds max (default is 30)
   * then return true
   *
   * @return
   */
  public boolean exceedsMaxEventsBeforeProposing() {
    return writtenMessageCount >= maxNumberOfEventsBeforeProposing;
  }

  /**
   * Set the file ready by syncing with the filesystem and renaming
   */
  private void setFileReady() {
    if (writtenMessageCount == 0) {
      logger.atFiner().log("RE No events to send. Waiting...");
      return;
    }

    logger.atFiner().log("RE Closing file and renaming to be picked up");
    try {
      lastWriter.close();
    } catch (IOException ex) {
      logger.atWarning().log("RE unable to close the file to send", ex);
    }

    if (syncFiles) {
      try {
        lastWriter.getFD().sync();
      } catch (IOException ex) {
        logger.atWarning().log("RE unable to sync the file to send", ex);
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
      writtenMessageCount++;
      //Set projectName upon writing the file
      lastProjectName = originalEvent.projectName;
      setEventsFileName(originalEvent);
    }
  }

  /**
   * Inner class dedicated to parsing json for an event timestamp
   * and nodeIdentity which will be used for labelling the events file.
   */
  static class ParseEventJson {
    public static String[] jsonEventParse(String jsonStr) {
      String[] jsonData = null;
      Object eventJsonObj = JSONValue.parse(jsonStr);

      if (eventJsonObj == null) {
        logger.atSevere().log("There was an error parsing the json from the event, %s", jsonStr);
        return null;
      }
      JSONObject eventJson = (JSONObject) eventJsonObj;
      if (eventJson.containsKey("eventTimestamp") && eventJson.containsKey("nodeIdentity")) {
        jsonData = new String[2];
        jsonData[0] = eventJson.get("eventTimestamp").toString();
        jsonData[1] = eventJson.get("nodeIdentity").toString();
      } else {
        logger.atSevere().log("RC Encountered an Event that did not contain an " +
            "originating nodeIdentity or an eventTimestamp. %s", jsonStr);
      }
      return jsonData;
    }
  }

  /**
   * Parse the timestamp and nodeIdentity from which the event originated
   * and label the events file them.
   *
   * @param originalEvent
   */
  private void setEventsFileName(final EventWrapper originalEvent) {
    String[] jsonData = ParseEventJson.jsonEventParse(originalEvent.event);
    if (jsonData == null || jsonData.length == 0) {
      logger.atSevere().log("Unable to set event filename as there was a JSON parsing error %s", originalEvent.event);
      return;
    }
    if (jsonData[0] != null && jsonData[0].matches("[0-9]+")) {
      eventsFileName = String.format(NEXT_EVENTS_FILE, jsonData[0], jsonData[1], 0);
    } else {
      eventsFileName = String.format(NEXT_EVENTS_FILE, System.currentTimeMillis(), getThisNodeIdentity(), 0);
      logger.atSevere().log("RE Could not parse JSON from events file correctly, Events file will be labeled with current system time and this NodeIdentity %s", eventsFileName);
    }
  }

  /**
   * Based on the project name, if the project is different, create/append a new file
   *
   * @throws FileNotFoundException
   */
  private void setCurrentEventsFile() throws FileNotFoundException {
    if (lastWriter == null) {
      createOutgoingEventsDir();
      if (outgoingReplEventsDirectory.exists() && outgoingReplEventsDirectory.isDirectory()) {
        lastWriterFile = new File(outgoingReplEventsDirectory, CURRENT_EVENTS_FILE);
        lastWriter = new FileOutputStream(lastWriterFile, true);
        lastProjectName = null;
        writtenMessageCount = 0;
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
   * Rename the current-events.json file to a unique filename
   * Resetting the lastWriter and count of the writtenMessageCount
   */
  private void renameAndReset() {
    File newFile = getNewFile();
    boolean renamed = lastWriterFile.renameTo(newFile);
    if (!renamed) {
      logger.atSevere().log("RE Could not rename file to be picked up, losing events! %s",
          lastWriterFile.getAbsolutePath());
    } else {
      logger.atFiner().log("RE Created new file %s to be proposed", newFile.getAbsolutePath());
    }
    lastWriter = null;
    lastWriterFile = null;
    lastWriteTime = 0;
    writtenMessageCount = 0;
    lastProjectName = null;
    Stats.totalPublishedLocalEventsProsals++;
  }

  /**
   * Return a unique events filename that takes the format
   * events-<milliseconds-since-epoch-timestamp>-<node.identity>-<non-zero-if-not-unique-bit>.json
   *
   * @return
   */
  private File getNewFile() {

    File uniqueFile = new File(outgoingReplEventsDirectory, eventsFileName);

    if (!eventsFileName.isEmpty()) {
      String[] jsonData = eventsFileName.split("-");
      //If for some reason the file is not set correctly, log an error
      if (!jsonData[1].matches("[0-9]+")) {
        logger.atSevere().log("RE, Event filename does not contain a timestamp.");
      }
      // Check the outgoing events.
      File outgoingEventFile = new File(outgoingReplEventsDirectory, eventsFileName);
      //In the unlikely event the file already exists in the dir, increment the 0 bit at the end of the filename.
      int nonUniqueIdentifier = 0;
      while (outgoingEventFile.exists()) {

        logger.atInfo().log("RE, Event file with name %s already exists, incrementing the nonUniqueIdentifier",
            outgoingEventFile.getAbsolutePath());

        //An outgoing events file with the same name already exists, so we need to create a
        //unique file. We increment, then use the nonUniqueIdentifier to create a new unique
        //file.
        //Example: events-1533899416153-node_id_Node1-00.json -> events-1533899416153-node_id_Node1-01.json

        uniqueFile = new File(outgoingReplEventsDirectory, String.format(NEXT_EVENTS_FILE,
            jsonData[1], thisNodeIdentity, ++nonUniqueIdentifier));

        //Check that the newly created file also doesn't exist. If it does exist we will continue
        //round the loop. If it doesn't exist, we break out of the loop and return the uniqueFile.
        if (!uniqueFile.exists()) {
          break;
        }
      }
    } else {
      logger.atSevere().log("RE Something has gone wrong, Events file name was not set correctly");
    }
    return uniqueFile;
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
        logger.atSevere().log("RE %s path cannot be created! Replicated events will not work!", incomingReplEventsDirectory.getAbsolutePath());
        return result;
      }

      logger.atInfo().log("RE %s created.", incomingReplEventsDirectory.getAbsolutePath());
    }
    try {
      File[] listFiles = incomingReplEventsDirectory.listFiles(incomingEventsToReplicateFileFilter);
      if (listFiles == null) {
        logger.atSevere().log("RE Cannot read files in directory %s. Too many files open?", incomingReplEventsDirectory, new IllegalStateException("RE Cannot read files"));
      } else if (listFiles.length > 0) {
        logger.atFiner().log("RE Found %s files", listFiles.length);

        Arrays.sort(listFiles);
        for (File file : listFiles) {
          //Adding debug logging to allow for checking the sorting of events files
          logger.atFiner().log("Reading incoming event file : %s", file.getName());
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
                reader.close(); // superfluos?
              }
            }

            int failedEvents = publishEvents(bos.toByteArray());

            // if some events failed copy the file to the failed directory
            if (failedEvents > 0) {
              logger.atSevere().log("RE There was %s failed events in this file %s", failedEvents, file.getAbsolutePath());
              Persister.moveFileToFailed(incomingReplEventsDirectory, file);
            }

            result = true;

            boolean deleted = file.delete();
            if (!deleted) {
              logger.atSevere().log("RE Could not delete file %s", file.getAbsolutePath());
            }
          } catch (IOException e) {
            logger.atSevere().log("RE while reading file %s", file.getAbsolutePath(), e);
          }
        }
      }
    } catch (RuntimeException e) {
      logger.atSevere().withCause(e).log("RE error while reading events from incoming queue");
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
   * From the bytes we read from disk, which the replicator provided, we
   * recreate the event using the name of the class embedded in the json text.
   * We then add the replicated flag to the object to avoid loops in sending
   * this event over and over again
   *
   * @param eventsBytes
   */
  private int publishEvents(byte[] eventsBytes) {
    logger.atFiner().log("RE Trying to publish original events...");

    String[] events = new String(eventsBytes, UTF_8).split("\n");
    Stats.totalPublishedForeignEventsBytes += eventsBytes.length;
    Stats.totalPublishedForeignEventsProsals++;
    int failedEvents = 0;

    for (String event : events) {
      Stats.totalPublishedForeignEvents++;
      if (event.length() > 2) {
        try {
          EventWrapper changeEventWrapper = gson.fromJson(event, EventWrapper.class);

          if (changeEventWrapper == null) {
            logger.atSevere().log("RE fromJson method returned null for %s", event);
            failedEvents++;
            continue;
          }

          Stats.totalPublishedForeignGoodEventsBytes += eventsBytes.length;
          Stats.totalPublishedForeignGoodEvents++;
          synchronized (eventListeners) {
            Stats.totalPublishedForeignEventsByType.add(changeEventWrapper.originator);
            Set<GerritPublishable> clients = eventListeners.get(changeEventWrapper.originator);
            if (clients != null) {
              if (changeEventWrapper.originator == EventWrapper.Originator.FOR_REPLICATOR_EVENT) {
                continue;
              }
              for (GerritPublishable gp : clients) {
                try {
                  boolean result = gp.publishIncomingReplicatedEvents(changeEventWrapper);

                  if (result == false) {
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
      } else {
        logger.atSevere().withCause(new Exception("Internal error, event is empty: " + event)).log("RE event GSON string is empty!");
        // its not really a failedevent as its no event. so not bumping up the counter.
      }
    }

    return failedEvents;
  }

  final static class IncomingEventsToReplicateFileFilter implements FileFilter {
    // These values are just to do a minimal filtering
    static final String FIRST_PART = "events-";
    static final String LAST_PART = ".json";

    @Override
    public boolean accept(File pathname) {
      String name = pathname.getName();
      try {
        if (name.startsWith(FIRST_PART) && name.endsWith(LAST_PART)) {
          return true;
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("File %s is not allowed here, remove it please ", pathname);
      }
      return false;
    }
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

  private static boolean readConfiguration() {
    boolean result = false;
    try {
      // Used for internal integration tests at WANdisco
      String gitConfigLoc = System.getProperty("GIT_CONFIG", System.getenv("GIT_CONFIG"));
      if (Strings.isNullOrEmpty(gitConfigLoc) && System.getenv("GIT_CONFIG") == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }

      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
      try {
        config.load();
      } catch (ConfigInvalidException e) {
        // Configuration file is not in the valid format, throw exception back.
        throw new IOException(e);
      }

      // N.B. This is for DEBUG use only.
      // Allow for the gitms system to be disabled, via an override java property or system environment value.
      // Allow the running of vanilla non replicated integration tests, and other simple scenarios.
      internalLogEnabled = getOverrideBehaviour(GERRITMS_INTERNAL_LOGGING);

      if (internalLogEnabled) {
        internalLogFile = new File(new File(DEFAULT_BASE_DIR), "replEvents.log"); // used for debug
      }

      File applicationProperties;
      try {
        String appProperties = config.getString("core", null, "gitmsconfig");

        if (Strings.isNullOrEmpty(appProperties)) {
          // there is no application properties location, just before we blow up, check a debug setting
          // which allows the gerrit instance to behave like a normal instance so we can run all the default gerrit
          // tests like a vanilla system could.
          if (isReplicationDisabled()) {
            logger.atWarning().log("GitMS integration has been disabled allowing Gerrit to work non-replicated.");
            return false;
          }

          throw new NullPointerException("Missing required core.gitmsconfig configuration value.");
        }
        applicationProperties = new File(appProperties);
        // GER-662 NPE thrown if GerritMS is started without a reference to a valid GitMS application.properties file.
      } catch (NullPointerException exception) {
        throw new FileNotFoundException("GerritMS cannot continue in replication mode without a valid GitMS application.properties file referenced in its .gitconfig file.");
      }

      // If application properties can't be found or read using the gitconfig setting,
      // try default location 1. "/opt/wandisco/xxx
      if (!applicationProperties.exists() || !applicationProperties.canRead()) {
        logger.atWarning().log("Could not find/read (1) " + applicationProperties);
        applicationProperties = new File(DEFAULT_MS_APPLICATION_PROPERTIES, "application.properties");
      }

      // if it still can't be found or read, get out.
      if (!applicationProperties.exists() || !applicationProperties.canRead()) {
        logger.atWarning().log("Could not find/read (2) " + applicationProperties);
        defaultBaseDir = DEFAULT_BASE_DIR + File.separator + REPLICATED_EVENTS_DIRECTORY_NAME;
        throw new FileNotFoundException("GerritMS cannot continue in replication mode without a valid GitMS application.properties file.");
      }

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
          defaultBaseDir += File.separator + REPLICATED_EVENTS_DIRECTORY_NAME;
        }

        incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED, "false"));

        //Getting the node identity that will be used to determine the originating node for each instance.
        thisNodeIdentity = props.getProperty("node.id");

        //Configurable for the maximum amount of events allowed in the outgoing events file before proposing.
        maxNumberOfEventsBeforeProposing = Integer.parseInt(
            cleanLforLong(props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, DEFAULT_MAX_EVENTS_PER_FILE)));

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

        logger.atInfo().log("Property %s=%s", new Object[]{GERRIT_REPLICATED_EVENTS_BASEPATH, defaultBaseDir});

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
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("While reading GerritMS properties file");
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("While loading the .gitconfig file");
    }
    return false;
  }

  /**
   * Utility method to get the system override properties and returns them as a boolean
   * indicating whether they are enabled / disabled.
   * @param overrideName
   * @return
   */
  private static boolean getOverrideBehaviour(String overrideName) {
    return Boolean.parseBoolean(
        System.getProperty(overrideName, System.getenv(overrideName)));
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
