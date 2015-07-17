package com.google.gerrit.common;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gerrit.server.events.ChangeEventWrapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SEND = "gerrit.replicated.events.enabled.send";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES = "gerrit.replicated.events.enabled.sync.files";
  public static final String GERRIT_REPLICATED_EVENTS_BASEPATH = "gerrit.replicated.events.basepath";
  public static final String GERRIT_EVENT_BASEPATH = "gerrit.events.basepath";
  public final static String GERRIT_RETRY_EVENTS_DIRECTORY="failed_retry";  // must match the one in GerritUpdateIndexProposalHandler
  public final static String GERRIT_DEFINITELY_FAILED_EVENTS_DIRECTORY="failed_definitely"; // as in GerritUpdateIndexProposalHandler
  public static final String GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED = "gerrit.replicated.events.incoming.gzipped";
  public static final String GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "gerrit.replicated.events.secs.on.queue";
  public static final String GERRIT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "gerrit.replicated.events.secs.before.proposing";
  public static final String GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED = "gerrit.replicated.cache.names.not.to.reload";
  public static final String GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING = "gerrit.replicated.events.max.append.before.proposing";
  public static final String ENC = "UTF-8"; // From BaseCommand
  public static final String DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "2";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "5";
  public static final String DEFAULT_MAX_EVENTS_PER_FILE = "30"; // as shown by statistics this means less than 2K gzipped proposals
  public static final int    DEFAULT_QUEUE_TIMEOUT = 5;
  public static final String CURRENT_EVENTS_FILE = "current-events.json";
  public static final String TIME_PH = "%TIME%";
  public static final String NEXT_EVENTS_FILE = "events-"+TIME_PH+"-new.json";
  public static final String GERRIT_REPLICATION_THREAD_NAME = "ReplicatorStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String REPLICATED_EVENTS_DIRECTORY_NAME = "replicated_events";
  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");
  public static final String OUTGOING_DIR = "outgoing";
  public static final String INCOMING_DIR = "incoming";
  public static final boolean internalLogEnabled = false;
  private static File replicatedEventsBaseDirectory = null;
  private static File outgoingReplEventsDirectory = null;
  private static File incomingReplEventsDirectory = null;
  private static long maxSecsToWaitBeforeProposingEvents=15;
  private static int maxNumberOfEventsBeforeProposing=30; // as shown by statistics this means less than 2K gzipped proposals
  private static final ArrayList<String> cacheNamesNotToReload = new ArrayList<>();
  private static final IncomingEventsToReplicateFileFilter incomingEventsToReplicateFileFilter = new IncomingEventsToReplicateFileFilter();
  private static boolean incomingEventsAreGZipped = false; // on the landing node the text maybe already unzipped by the replicator
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static boolean syncFiles=false;
  private static String defaultBaseDir;
  private static File failedRetryDir = null;
  private static File failedDefinitelyDir = null;
  private static volatile Replicator instance = null;
  private static final Gson gson = new Gson();
  private static Config gerritConfig = null;

  private FileOutputStream lastWriter = null;
  private String lastProjectName = null;
  private int lastWrittenMessages = 0;
  private File lastWriterFile = null;
  private long lastTimeCreated;
  private boolean finished = false;

  // Statistics begin
  private long totalPublishedForeignEventsProsals = 0;
  private long totalPublishedForeignEvents = 0;
  private long totalPublishedForeignGoodEvents = 0;
  private long totalPublishedForeignGoodEventsBytes = 0;
  private long totalPublishedForeignEventsBytes = 0;
  private final Multiset<ChangeEventWrapper.Originator> totalPublishedForeignEventsByType = HashMultiset.create();

  private long totalPublishedLocalEventsProsals = 0;
  private long totalPublishedLocalEvents = 0;
  private long totalPublishedLocalGoodEvents = 0;
  private long totalPublishedLocalGoodEventsBytes = 0;
  private long totalPublishedLocalEventsBytes = 0;
  private final Multiset<ChangeEventWrapper.Originator> totalPublishedLocalEventsByType = HashMultiset.create();
  
  private long lastCheckedFailedDirTime = 0;
  private long lastCheckedDefFailedDirTime = 0;
  private int lastFailedDirValue = -1;
  private int lastDefFailedDirValue = -1;
  public static long DEFAULT_STATS_UPDATE_TIME = 20000L;
  
  // Statistics end
  
  public interface GerritPublishable {
     public boolean publishIncomingReplicatedEvents(ChangeEventWrapper newEvent);
  }
  
  private final static Map<ChangeEventWrapper.Originator,Set<GerritPublishable>> eventListeners = new HashMap<>();
  
  // Queue of events to replicate
  private final ConcurrentLinkedQueue<ChangeEventWrapper> queue =   new ConcurrentLinkedQueue<>();

  public static Replicator getInstance() {
    
    if (internalLogEnabled) {
      internalLogFile = new File(new File(DEFAULT_BASE_DIR),"replEvents.log"); // used for debug
    }
    if (instance == null) {
      synchronized(gson) {
        if (instance == null) {
          boolean configOk = readConfiguration();
          log.info("RE Configuration read: ok? {}",configOk);

          replicatedEventsBaseDirectory = new File(defaultBaseDir);
          outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory,OUTGOING_DIR);
          incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory,INCOMING_DIR);

          if (eventReaderAndPublisherThread == null) {
            instance = new Replicator();

            eventReaderAndPublisherThread = new Thread(instance);
            eventReaderAndPublisherThread.setName(GERRIT_REPLICATION_THREAD_NAME);
            eventReaderAndPublisherThread.start();
          } else {
            log.error("RE Thread {} is already running!",GERRIT_REPLICATION_THREAD_NAME);
            logMe("Thread "+GERRIT_REPLICATION_THREAD_NAME+" is already running!",null);
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
  
  public static void subscribeEvent(ChangeEventWrapper.Originator eventType,GerritPublishable toCall) {
    synchronized(eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set == null) {
        set = new HashSet<>();
        eventListeners.put(eventType, set);
      }
      set.add(toCall);
      log.info("Subscriber added to {}",eventType);
    }
  }

  public static void unsubscribeEvent(ChangeEventWrapper.Originator eventType,GerritPublishable toCall) {
    synchronized(eventListeners) {
      Set<GerritPublishable> set = eventListeners.get(eventType);
      if (set != null) {
        set.remove(toCall);
        log.info("Subscriber removed of type {}",eventType);
      }
    }
  }
  
  /**
   * This functions is just to log something when the Gerrit logger is not yet available
   * and you need to know if it's working. To be used for debugging purposes.
   * Gerrit will not log anything until the log system will be initialized.
   * @param msg
   * @param t 
   */
  static void logMe(String msg, Throwable t) {
    if (!internalLogEnabled) {
      return;
    }
    if (!outgoingReplEventsDirectory.exists() && !outgoingReplEventsDirectory.mkdirs()) {
      System.err.println("Cannot create directory for internal logging: "+outgoingReplEventsDirectory);
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
   * 
   * After this or while this happens, the thread will also look for files
   * coming from the replicator, which need to be read and published as they
   * are incoming events.
   */
  @Override
  @SuppressWarnings("SleepWhileInLoop")
  public void run() {
    log.info("RE ReplicateEvents thread is starting...");
    logMe("ReplicateEvents thread is starting...",null);
    
    // we need to make this thread never fail, otherwise we'll lose events.
    while (!finished) {
      try {
        while (true) {
          // poll for the events published by gerrit and write them to disk
          boolean eventGot = pollAndWriteOutgoingEvents();
          // Look for files saved by the replicator which need to be published
          boolean published = readAndPublishIncomingEvents();
          if (!eventGot && !published) {
            Thread.sleep(1000);
          }
        }
      } catch (InterruptedException e) {
        log.info("RE Exiting",e);
        finished = true;
      } catch(RuntimeException e) {
        log.error("RE Unexpected exception",e);
        logMe("Unexpected exception",e);
      } catch(Exception e) {
        log.error("RE Unexpected exception",e);
        logMe("Unexpected exception",e);
      }
    }
    log.error("RE Thread finished");
    logMe("Thread finished",null);
    clearThread();
    finished = true;
  }
  
  public void queueEventForReplication(ChangeEventWrapper event) {
    queue.offer(event); // queue is unbound, no need to check for result
  }

  public long getTotalPublishedForeignEventsProsals() {
    return totalPublishedForeignEventsProsals;
  }

  public long getTotalPublishedForeignEvents() {
    return totalPublishedForeignEvents;
  }

  public long getTotalPublishedForeignGoodEvents() {
    return totalPublishedForeignGoodEvents;
  }

  public long getTotalPublishedForeignEventsBytes() {
    return totalPublishedForeignEventsBytes;
  }

  public long getTotalPublishedForeignGoodEventsBytes() {
    return totalPublishedForeignGoodEventsBytes;
  }

  public ImmutableMultiset<ChangeEventWrapper.Originator> getTotalPublishedForeignEventsByType() {
    return ImmutableMultiset.copyOf(totalPublishedForeignEventsByType);
  }

  public long getTotalPublishedLocalEventsProsals() {
    return totalPublishedLocalEventsProsals;
  }

  public long getTotalPublishedLocalEvents() {
    return totalPublishedLocalEvents;
  }

  public long getTotalPublishedLocalGoodEvents() {
    return totalPublishedLocalGoodEvents;
  }

  public long getTotalPublishedLocalEventsBytes() {
    return totalPublishedLocalEventsBytes;
  }

  public long getTotalPublishedLocalGoodEventsBytes() {
    return totalPublishedLocalGoodEventsBytes;
  }

  public ImmutableMultiset<ChangeEventWrapper.Originator> getTotalPublishedLocalEventsByType() {
    return ImmutableMultiset.copyOf(totalPublishedLocalEventsByType);
  }

  public int getFailedRetryIndexingEvents() {
    int result = -1;
    if (failedRetryDir != null) {
      long now = System.currentTimeMillis();
      if (now - lastCheckedFailedDirTime > DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for DEFAULT_STATS_UPDATE_TIME ms, so that continous requests do not disturb
        lastFailedDirValue = failedRetryDir.listFiles().length;
        lastCheckedFailedDirTime = now;
      }
      result = lastFailedDirValue;
    }
    return result;
  }

  public int getFailedDefinitelyIndexingEvents() {
    int result = -1;
    if (failedDefinitelyDir != null) {
      long now = System.currentTimeMillis();
      if (now - lastCheckedDefFailedDirTime > DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for DEFAULT_STATS_UPDATE_TIME ms, so that continous requests do not disturb
        lastDefFailedDirValue = failedDefinitelyDir.listFiles().length;
        lastCheckedDefFailedDirTime = now;
      }
      result = lastDefFailedDirValue;
    }
    return result;
  }
  
  /**
   * poll for the events published by gerrit and send to the other nodes through files
   * read by the replicator
   */
  private boolean pollAndWriteOutgoingEvents() throws InterruptedException {
    boolean eventGot = false;
    ChangeEventWrapper newEvent;
    newEvent = queue.poll();
    if (newEvent != null) {
      try {
        // append the event to the current file
        appendToFile(newEvent);
        eventGot = true;
      } catch (IOException e) {
        log.error("RE Cannot create buffer file for events queueing!",e);
      }
    }
    // Prepare the file to be picked up by the replicator
    setFileReadyIfFull(false);
    return eventGot;
  }
  
  /**
   * This will create append to the current file the last event received.
   * If the project name of the this event is different from the the last one,
   * then we need to create a new file anyway, cause we want to pack events in one
   * file only if the are for the same project
   *
   * @param originalEvent
   * @return true if the event was successfully appended to the file
   * @throws IOException 
   */
  private boolean appendToFile(final ChangeEventWrapper originalEvent) throws IOException {
    boolean result = false;

    totalPublishedLocalEvents++;
    createOrCheckCurrentEventsFile();
    if (lastWriter != null) {
      if (lastProjectName != null && !lastProjectName.equals(originalEvent.projectName)) {
        // event refers to another project, so we need to cut a new file anyway
        setFileReadyIfFull(true);
        createOrCheckCurrentEventsFile();
      }
      final String msg = gson.toJson(originalEvent)+'\n';
      log.debug("RE Last json to be sent: {}",msg);
      byte[] bytes = msg.getBytes(ENC);
      totalPublishedLocalEventsBytes += bytes.length;
      lastWriter.write(bytes);
      lastWrittenMessages++;
      lastProjectName = originalEvent.projectName;
      result = true;
      
      totalPublishedLocalGoodEventsBytes += bytes.length;
      totalPublishedLocalGoodEvents++;
      totalPublishedLocalEventsByType.add(originalEvent.originator);
    } else {
      IOException writerIsNull = new IOException("Internal error, null writer");
      log.error("RE Cannot write event to any file! lastWriter is null!",writerIsNull);
      throw writerIsNull;
    }
    return result;
  }

  private void createOrCheckCurrentEventsFile() throws FileNotFoundException, UnsupportedEncodingException {
    if (lastWriter == null) {
      if (!outgoingReplEventsDirectory.exists()) {
        boolean mkdirs = outgoingReplEventsDirectory.mkdirs();
        if (!mkdirs) {
          FileNotFoundException cannotCreateDir = new FileNotFoundException("Cannot create directory");
          log.error("RE Could not create replicated events directory!", cannotCreateDir);
          throw cannotCreateDir;
        } else {
          log.info("RE Created directory {} for replicated events",outgoingReplEventsDirectory.getAbsolutePath());
        }
      }
      if (outgoingReplEventsDirectory.exists() && outgoingReplEventsDirectory.isDirectory()) {
        lastWriterFile = new File(outgoingReplEventsDirectory,CURRENT_EVENTS_FILE);
        //lastWriter = new PrintWriter(lastWriterFile,ENC);
        lastWriter = new FileOutputStream(lastWriterFile,true);
        lastTimeCreated = System.currentTimeMillis();
        lastWrittenMessages = 0;
        lastProjectName = null;
      }
    }
  }

  private void setFileReadyIfFull(boolean projectIsDifferent) {
    long diff = System.currentTimeMillis() - lastTimeCreated;
    
    if (projectIsDifferent || diff > maxSecsToWaitBeforeProposingEvents*1000 || lastWrittenMessages >= maxNumberOfEventsBeforeProposing) {
      if (lastWrittenMessages == 0) {
        log.debug("RE No events to send. Waiting...");
        lastTimeCreated = System.currentTimeMillis();
      } else {
        log.debug("RE Closing file and renaming to be picked up {}",projectIsDifferent);
        try {
          lastWriter.close();
        } catch (IOException ex) {
          log.warn("RE unable to close the file to send",ex);
        }
        if (syncFiles) {
          try {
            lastWriter.getFD().sync();
          } catch (IOException ex) {
            log.warn("RE unable to sync the file to send", ex);
          }
        }
        File newFile = getNewFile();
        boolean renamed = lastWriterFile.renameTo(newFile);
        if (!renamed) {
          log.error("RE Could not rename file to be picked up, losing events! {}",lastWriterFile.getAbsolutePath());
        } else {
          log.debug("RE Created new file {} to be proposed", newFile.getAbsolutePath());
        }
        lastWriter = null;
        lastWrittenMessages = 0;
        lastProjectName = null;
        lastTimeCreated = System.currentTimeMillis();
        totalPublishedLocalEventsProsals++;
      }
    }
  }
  
  private File getNewFile()  {
    String currTime = ""+System.currentTimeMillis();
    File newFile = new File(outgoingReplEventsDirectory,NEXT_EVENTS_FILE.replaceFirst(TIME_PH, currTime));
    int i=1;
    while (newFile.exists() && i < 1000) {
      newFile = new File(outgoingReplEventsDirectory,NEXT_EVENTS_FILE.replaceFirst(TIME_PH, currTime+"-"+String.format("%03d", i++)));
    }
    if (newFile.exists()) {
      log.error("RE File {} already exists in the directory, please clear the way!",newFile.getAbsolutePath());
      // Try to rename offending file...
      boolean renamed = newFile.renameTo(new File(newFile.getParentFile(),"renamed-"+System.currentTimeMillis()));
      if (!renamed) {
        log.error("RE Could not rename offending file");
        currTime+="0";
      }
      newFile = new File(outgoingReplEventsDirectory,NEXT_EVENTS_FILE.replaceFirst(TIME_PH, currTime));
      if (newFile.exists() && !newFile.delete()) {
        log.error("RE incredibly could not delete that existing file {}",newFile.getAbsolutePath());
      }
    }
    return newFile;
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
        log.error("RE {} path cannot be created! Replicated events will not work!",incomingReplEventsDirectory.getAbsolutePath());
        return result;
      } else {
        log.info("RE {} created.",incomingReplEventsDirectory.getAbsolutePath());
      }
    }
    try {
      File[] listFiles = incomingReplEventsDirectory.listFiles(incomingEventsToReplicateFileFilter);
      if (listFiles.length > 0) {
        log.debug("RE Found {} files",listFiles.length);

        for (File file : listFiles) {
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
            
            publishEvents(bos.toByteArray());
            result = true;
            
            boolean deleted = file.delete();
            if (!deleted) {
              log.error("RE Could not delete file {}",file.getAbsolutePath());
            }
          } catch (IOException e) {
            log.error("RE while reading file {}",file.getAbsolutePath(), e);
          }
        }
      }
    } catch (RuntimeException e) {
      log.error("RE error while reading events from incoming queue", e);
    } catch (Exception e) {
      log.error("RE error while reading events from incoming queue", e);
    }
    return result;
  }
  
  private void copyFile(InputStream source, OutputStream dest) throws IOException {
    try (InputStream fis = source) {
      byte[] buf= new byte[4096];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }

  /**
   * From the bytes we read from disk, which the replicator provided, we
   * recreate the event using the name of the class embedded in the json text.
   * We then add the replicated flag to the object to avoid loops in sending
   * this event over and over again
   * @param eventsBytes 
   */
  private void publishEvents(byte[] eventsBytes) {
    log.debug("RE Trying to publish original events...");
    String[] events = new String(eventsBytes,StandardCharsets.UTF_8).split("\n");
    totalPublishedForeignEventsBytes += eventsBytes.length;
    totalPublishedForeignEventsProsals++;
    for (String event: events) {
      totalPublishedForeignEvents++;
      if (event.length() > 2) {
        try {
          ChangeEventWrapper changeEventWrapper = gson.fromJson(event, ChangeEventWrapper.class);
          totalPublishedForeignGoodEventsBytes += eventsBytes.length;
          totalPublishedForeignGoodEvents++;
          synchronized(eventListeners) {
            totalPublishedForeignEventsByType.add(changeEventWrapper.originator);
            Set<GerritPublishable> clients = eventListeners.get(changeEventWrapper.originator);
            if (clients != null) {
              for(GerritPublishable gp: clients) {
                try {
                  gp.publishIncomingReplicatedEvents(changeEventWrapper);
                } catch (Exception e) {
                  log.error("While publishing events",e);
                }
              }
            }
          }
        } catch(JsonSyntaxException e) {
          log.error("RE event has been lost. Could not rebuild obj using GSON",e);
        }
      } else {
        log.error("RE event GSON string is empy!", new Exception("Internal error, event is empty: "+event));
      }
    }
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
        log.error("File {} is not allowed here, remove it please ",pathname,e);
      }
      return false;
    }
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
      String gitConfigLoc = System.getenv("GIT_CONFIG");
      if (gitConfigLoc == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }
      
      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
      try {
        config.load();
      } catch (ConfigInvalidException e) {
        // Configuration file is not in the valid format, throw exception back.
        throw new IOException(e);
      }

      String appProperties = config.getString("core", null, "gitmsconfig");
      File applicationProperties = new File(appProperties);
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
            failedRetryDir = new File(defaultBaseDir,GERRIT_RETRY_EVENTS_DIRECTORY);
            failedDefinitelyDir = new File (defaultBaseDir,GERRIT_DEFINITELY_FAILED_EVENTS_DIRECTORY);
            defaultBaseDir+=File.separator+REPLICATED_EVENTS_DIRECTORY_NAME;
          }
          incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED,"false"));
          maxSecsToWaitBeforeProposingEvents = Long.parseLong(
              cleanLforLong(props.getProperty(GERRIT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
                  DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));
          maxNumberOfEventsBeforeProposing = Integer.parseInt(
              cleanLforLong(props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING,DEFAULT_MAX_EVENTS_PER_FILE)));
          
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
  
  private static String cleanLforLong(String property) {
    if (property != null && property.length() > 1 &&  (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0,property.length()-1);
    }
    return property;
  }
}
