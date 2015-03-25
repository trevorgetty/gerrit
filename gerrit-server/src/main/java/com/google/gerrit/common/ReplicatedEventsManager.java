/**
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * 
 * http://www.wandisco.com/
 */
package com.google.gerrit.common;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.ChangeEventWrapper;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.ChangeRestoredEvent;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.DraftPublishedEvent;
import com.google.gerrit.server.events.MergeFailedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.events.ReviewerAddedEvent;
import com.google.gerrit.server.events.SubmitEvent;
import com.google.gerrit.server.events.TopicChangedEvent;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
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
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS_POSIX_Java6;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author antoniochirizzi
 */
public final class ReplicatedEventsManager implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedEventsManager.class);
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SEND = "gerrit.replicated.events.enabled.send";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES = "gerrit.replicated.events.enabled.sync.files";
  public static final String GERRIT_REPLICATED_EVENTS_BASEPATH = "gerrit.replicated.events.basepath";
  public static final String GERRIT_EVENT_BASEPATH = "gerrit.events.basepath";
  public static final String GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED = "gerrit.replicated.events.incoming.gzipped";
  public static final String GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "gerrit.replicated.events.secs.on.queue";
  public static final String GERRIT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "gerrit.replicated.events.secs.before.proposing";
  public static final String ENC = "UTF-8"; // From BaseCommand
  public static final String DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "2";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "5";
  public static final String CURRENT_EVENTS_FILE = "current-events.json";
  public static final String TIME_PH = "%TIME%";
  public static final String NEXT_EVENTS_FILE = "events-"+TIME_PH+"-new.json";
  public static final String EVENTS_REPLICATION_THREAD_NAME = "EventsStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String REPLICATED_EVENTS_DIRECTORY_NAME = "replicated_events";
  public static final String DAFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");
  public static final String OUTGOING_DIR = "outgoing";
  public static final String INCOMING_DIR = "incoming";
  public static final boolean internalLogEnabled = false;
  private static File replicatedEventsBaseDirectory = null;
  private static File outgoingReplEventsDirectory = null;
  private static File incomingReplEventsDirectory = null;
  private static long maxSecsToWaitForEventOnQueue=5;
  private static long maxSecsToWaitBeforeProposingEvents=15;
  private static final IncomingEventsToReplicateFileFilter incomingEventsToReplicateFileFilter = new IncomingEventsToReplicateFileFilter();
  private static boolean incomingEventsAreGZipped = false; // on the landing node the text maybe already unzipped by the replicator
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static boolean replicatedEventsEnabled=false;
  private static boolean replicatedEventsSend=false;
  private static boolean replicatedEventsReceive=false;
  private static boolean syncFiles=false;
  private static String defaultBaseDir;
  private static ReplicatedEventsManager instance = null;
  private static final Gson gson = new Gson();
  private FileOutputStream lastWriter = null;
  private String lastProjectName = null;
  private int lastWrittenMessages = 0;
  private File lastWriterFile = null;
  private long lastTimeCreated;
  ChangeHookRunner changeHookRunner = null;
  private final SchemaFactory<ReviewDb> schema;
  private boolean finished = false;
  
  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 1024;
  // Queue of events to replicate
  private final LinkedBlockingQueue<ChangeEvent> queue =   new LinkedBlockingQueue<>(MAX_EVENTS);
  private final Object replicationLock = new Object();

  public static synchronized ReplicatedEventsManager hookOnListeners(ChangeHookRunner changeHookRunner,
      final SchemaFactory<ReviewDb> schema) {
    
    log.info("RE ReplicatedEvents hook called...");
    
    if (internalLogEnabled) {
      internalLogFile = new File(new File(DAFAULT_BASE_DIR),"replEvents.log"); // used for debug
    }
    logMe("ReplicatedEvents hook called, with "+changeHookRunner+" set",null);
    
    boolean configOk = readConfiguration();
    log.info("RE Configuration read: ok? {}, replicatedEvent are enabled? {}",new Object[] {configOk,replicatedEventsEnabled});
    
    if (replicatedEventsEnabled) {
      replicatedEventsBaseDirectory = new File(defaultBaseDir);
      outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory,OUTGOING_DIR);
      incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory,INCOMING_DIR);

      if (eventReaderAndPublisherThread == null) {
        instance = new ReplicatedEventsManager(changeHookRunner,schema);

        eventReaderAndPublisherThread = new Thread(instance);
        eventReaderAndPublisherThread.setName(EVENTS_REPLICATION_THREAD_NAME);
        eventReaderAndPublisherThread.start();
      } else {
        log.error("RE Thread {} is already running!",EVENTS_REPLICATION_THREAD_NAME);
        logMe("Thread "+EVENTS_REPLICATION_THREAD_NAME+" is already running!",null);
      }
    } else {
      if (instance != null) {
        instance.finished = true;
      }
      instance = null;
    }
    return instance;
  }
  
  private ReplicatedEventsManager(ChangeHookRunner changeHookRunner, final SchemaFactory<ReviewDb> schema) {
    this.changeHookRunner=changeHookRunner;
    this.schema=schema;
    log.info("RE ReplicatedEvents instance added");
    logMe("ReplicatedEvents instance added",null);
  }
  
  private ReplicatedEventsManager() {
    schema = null;
  }
  
  private static synchronized void clearThread() {
    eventReaderAndPublisherThread = null;
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
  
  public final ChangeListener listener = new ChangeListener() {
    @Override
    public void onChangeEvent(final ChangeEvent event) {
      if (!event.replicated) {
        offer(event);
      }
    }
  };

  private void offer(final ChangeEvent event) {
    synchronized (replicationLock) {
      if (!queue.offer(event)) {
        log.error("RE Could not queue an event! (The event will not be replicated)", new Exception("Cannot offer event to queue"));
      }
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
  public void run() {
    log.info("RE ReplicateEvents thread is starting...");
    logMe("ReplicateEvents thread is starting...",null);
    
    // we need to make this thread never fail, otherwise we'll lose events.
    while (!finished) {
      try {
        while (true) {
          if (replicatedEventsSend) {
            // poll for the events published by gerrit and write them to disk
            pollAndWriteOutgoingEvents();
          }
          if (replicatedEventsReceive) {
            // Look for files saved by the replicator which need to be published
            readAndPublishIncomingEvents();
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

  /**
   * poll for the events published by gerrit and send to the other nodes through files
   * read by the replicator
   */
  private boolean pollAndWriteOutgoingEvents() throws InterruptedException {
    boolean eventGot = false;
    ChangeEvent newEvent;
    newEvent = queue.poll(maxSecsToWaitForEventOnQueue, TimeUnit.SECONDS);
    if (newEvent != null && !newEvent.replicated) {
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
   * @param newEvent
   * @return true if the event was successfully appended to the file
   * @throws IOException 
   */
  private boolean appendToFile(ChangeEvent newEvent) throws IOException {
    boolean result = false;

    createOrCheckCurrentEventsFile();
    if (lastWriter != null) {
      ChangeEventInfo changeEventInfo = getChangeEventInfo(newEvent);
      if (changeEventInfo.isSupported()) {
        if (lastProjectName != null && !lastProjectName.equals(changeEventInfo.projectName)) {
          // event refers to another project, so we need to cut a new file anyway
          setFileReadyIfFull(true);
          createOrCheckCurrentEventsFile();
        }
        final ChangeEventWrapper originalEvent = new ChangeEventWrapper(newEvent,changeEventInfo);
        final String msg = gson.toJson(originalEvent)+'\n';
        //lastWriter.println(msg);
        lastWriter.write((msg).getBytes(ENC));
        lastWrittenMessages++;
        lastProjectName = changeEventInfo.projectName;
        result = true;
      } else {
        IOException eventNotSupported = new IOException("Internal error, event is not supported.");
        log.error("RE Event is not supported! Will not be replicated.",eventNotSupported);
        throw eventNotSupported;
      }
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
    
    if (projectIsDifferent || diff > maxSecsToWaitBeforeProposingEvents*1000) {
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
          log.error("RE Could not rename file to be picked up, losing events!");
        } else {
          log.debug("RE Created new file {} to be proposed", newFile.getAbsolutePath());
        }
        lastWriter = null;
        lastWrittenMessages = 0;
        lastProjectName = null;
        lastTimeCreated = System.currentTimeMillis();
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
   */
  private void readAndPublishIncomingEvents() {
    if (!incomingReplEventsDirectory.exists()) {
      if (!incomingReplEventsDirectory.mkdirs()) {
        log.error("RE {} path cannot be created! Replicated events will not work!",incomingReplEventsDirectory.getAbsolutePath());
        return;
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
    for (String event: events) {
      if (event.length() > 2) {
        try {
          ChangeEventWrapper changeEventWrapper = gson.fromJson(event, ChangeEventWrapper.class);
          try {
            Class<?> eventClass = Class.forName(changeEventWrapper.className);
            ChangeEvent originalEvent = (ChangeEvent) gson.fromJson(changeEventWrapper.changeEvent, eventClass);
            log.debug("RE Original event: {}",originalEvent.toString());
            originalEvent.replicated = true;
            if (!publishIncomingReplicatedEvents(originalEvent)) {
              log.error("RE event has been lost, not supported");
            }
          } catch(ClassNotFoundException e) {
            log.error("RE event has been lost. Could not find {}",changeEventWrapper.className,e);
          }
        } catch(JsonSyntaxException e) {
          log.error("RE event has been lost. Could not rebuild obj using GSON",e);
        }
      } else {
        log.error("RE event GSON string is empy!", new Exception("Internal error, event is empty: "+event));
      }
    }
  }
  
  /**
   * Since the event can be of many different types, and since the Gerrit engineers didn't want
   * to put the ChangeAttribute in the main abstract class, we have to analyze every 
   * single event type and extract the relevant information
   * 
   * @param newEvent
   * @return false if the event is not supported
   */
  public ChangeEventInfo getChangeEventInfo(ChangeEvent newEvent) {
    ChangeEventInfo changeEventInfo = new ChangeEventInfo();
    if (newEvent instanceof com.google.gerrit.server.events.ChangeAbandonedEvent) {
      changeEventInfo.setChangeAttribute(((ChangeAbandonedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeMergedEvent) {
      changeEventInfo.setChangeAttribute(((ChangeMergedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeRestoredEvent) {
      changeEventInfo.setChangeAttribute(((ChangeRestoredEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.CommentAddedEvent) {
      changeEventInfo.setChangeAttribute(((CommentAddedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.CommitReceivedEvent) {
      CommitReceivedEvent event = (CommitReceivedEvent) newEvent;
      changeEventInfo.setProjectName(event.project.getName());
      changeEventInfo.setBranchName(new Branch.NameKey(event.project.getNameKey(), completeRef(event.refName)));
    } else if (newEvent instanceof com.google.gerrit.server.events.DraftPublishedEvent) {
      changeEventInfo.setChangeAttribute(((DraftPublishedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.MergeFailedEvent) {
      changeEventInfo.setChangeAttribute(((MergeFailedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.PatchSetCreatedEvent) {
      changeEventInfo.setChangeAttribute(((PatchSetCreatedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.RefUpdatedEvent) {
      RefUpdatedEvent event = (RefUpdatedEvent) newEvent;
      if (event.refUpdate != null) {
        changeEventInfo.setProjectName(event.refUpdate.project);
        changeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(event.refUpdate.project), completeRef(event.refUpdate.refName)));
      } else {
        log.error("RE {} is not supported, project name or refupdate is null!", newEvent.getClass().getName());
        changeEventInfo.supported = false;
      }
    } else if (newEvent instanceof com.google.gerrit.server.events.ReviewerAddedEvent) {
      changeEventInfo.setChangeAttribute(((ReviewerAddedEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.SubmitEvent) {
      changeEventInfo.setChangeAttribute(((SubmitEvent) newEvent).change);
    } else if (newEvent instanceof com.google.gerrit.server.events.TopicChangedEvent) {
      changeEventInfo.setChangeAttribute(((TopicChangedEvent) newEvent).change);
    } else {
      log.error("RE {} is not supported!", newEvent.getClass().getName());
      changeEventInfo.supported = false;
    }
    return changeEventInfo;
  }
  
  /**
   * Publishes the event calling the postEvent function in ChangeHookRunner
   * 
   * @param newEvent
   * @return 
   */
  private boolean publishIncomingReplicatedEvents(ChangeEvent newEvent) {
    ChangeEventInfo changeEventInfo = getChangeEventInfo(newEvent);

    if (changeEventInfo.isSupported()) {
      log.debug("RE going to fire event...");
      ReviewDb db = null;
      try {
        db = schema.open();
        if (changeEventInfo.changeAttr != null) {
          log.debug("RE using changeAttr: {}...", changeEventInfo.changeAttr);
          Change change = db.changes().get(Change.Id.parse(changeEventInfo.changeAttr.number));
          log.debug("RE got change from db: {}", change);
          changeHookRunner.postEvent(change, newEvent, db);
        } else if (changeEventInfo.branchName != null) {
          log.debug("RE using branchName: {}", changeEventInfo.branchName);
          changeHookRunner.postEvent(changeEventInfo.branchName, newEvent);
        } else {
          log.error("RE Internal error, it's *supported*, but refs is null",new Exception("refs is null for supported event"));
          changeEventInfo.supported = false;
        }
      } catch (OrmException e) {
        log.error("While trying to publish a replicated event", e);
      } finally {
        // since it's a schema.open() call to get the db, we are closing it
        if (db != null) {
          db.close();
        }
      }
    }
    return changeEventInfo.supported;
  }

  // for the authentication in Gerrit
  private String completeRef(String refName) {
    if (refName == null) {
      return "";
    } else if (refName.contains("/")) {
      return refName;
    } else {
      return "refs/heads/"+refName;
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

  public static class ChangeEventInfo {
    private ChangeAttribute changeAttr = null;
    private Branch.NameKey branchName = null;
    private String projectName = null;
    private boolean supported = false;
    
    public void setChangeAttribute(ChangeAttribute changeAttr) {
      this.changeAttr = changeAttr;
      if (changeAttr != null) {
        projectName = changeAttr.project;
        supported = true;
      }
    }
    
    public void setProjectName(String projectName) {
      this.projectName = projectName;
    }
    
    public void setBranchName(Branch.NameKey branchName) {
      this.branchName = branchName;
      supported = true;
    }

    public ChangeAttribute getChangeAttr() {
      return changeAttr;
    }

    public Branch.NameKey getBranchName() {
      return branchName;
    }

    public String getProjectName() {
      return projectName;
    }

    public boolean isSupported() {
      return supported;
    }
  }
  
  private static boolean readConfiguration() {
    boolean result = false;
    try {
      // Used for internal integration tests at WANdisco
      String gitConfigLoc = System.getenv("GIT_CONFIG");
      if (gitConfigLoc == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }
      
      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), new FS_POSIX_Java6());
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
        defaultBaseDir = DAFAULT_BASE_DIR+"/"+REPLICATED_EVENTS_DIRECTORY_NAME;
      } else {
        Properties props = new Properties();
        try (FileInputStream propsFile = new FileInputStream(applicationProperties)) {
          props.load(propsFile);
          replicatedEventsSend = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_SEND,"true"));
          replicatedEventsReceive = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE,"true"));
          replicatedEventsEnabled = replicatedEventsReceive || replicatedEventsSend;
          if (replicatedEventsEnabled) {
            syncFiles = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES, "false"));

            // The user can set a different path specific for the replicated events. If it's not there
            // then the usual GERRIT_EVENT_BASEPATH will be taken.
            defaultBaseDir = props.getProperty(GERRIT_REPLICATED_EVENTS_BASEPATH);
            if (defaultBaseDir == null) {
              defaultBaseDir = props.getProperty(GERRIT_EVENT_BASEPATH);
              if (defaultBaseDir == null) {
                defaultBaseDir = DAFAULT_BASE_DIR;
              }
              defaultBaseDir+="/"+REPLICATED_EVENTS_DIRECTORY_NAME;
            }
            incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED,"false"));
            maxSecsToWaitForEventOnQueue = Long.parseLong(
                cleanLforLong(props.getProperty(GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE,DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE)));
            maxSecsToWaitBeforeProposingEvents = Long.parseLong(
                cleanLforLong(props.getProperty(GERRIT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
                    DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));
            log.info("Property {}={}",new Object[] {GERRIT_REPLICATED_EVENTS_BASEPATH,defaultBaseDir});
          } else {
            log.info("Replicated events are disabled"); // This will likely not appear in the log... cause the log is not yet ready
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
