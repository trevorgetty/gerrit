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
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author antoniochirizzi
 */
public final class ReplicatedEventsManager implements Runnable,Replicator.GerritPublishable {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedEventsManager.class);
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SEND = "gerrit.replicated.events.enabled.send";
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL = "gerrit.replicated.events.enabled.receive.original";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT = "gerrit.replicated.events.enabled.receive.distinct";
  public static final String GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT = "gerrit.replicated.events.enabled.local.republish.distinct";
  public static final String GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX = "gerrit.replicated.events.distinct.prefix";
  public static final String DEFAULT_DISTINCT_PREFIX = "REPL-";
  public static final String GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "gerrit.replicated.events.secs.on.queue";
  public static final String ENC = "UTF-8"; // From BaseCommand
  public static final String DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "2";
  public static final String EVENTS_REPLICATION_THREAD_NAME = "EventsStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String DAFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");

  public static final boolean internalLogEnabled = false;
  private static long maxSecsToWaitForEventOnQueue=5;
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static String distinctEventPrefix = DEFAULT_DISTINCT_PREFIX;
  private static boolean replicatedEventsReceive=true; // receive and original are synonym
  private static boolean replicatedEventsReplicateOriginalEvents = true; // receive and original are synonym
  private static boolean replicatedEventsReplicateDistinctEvents = false;
  private static boolean receiveReplicatedEventsEnabled = true;
  private static boolean replicatedEventsEnabled=true;
  private static boolean replicatedEventsSend=true;
  private static boolean localRepublishEnabled = false;
  private static ReplicatedEventsManager instance = null;
  private static final Gson gson = new Gson();
  private ChangeHookRunner changeHookRunner = null;
  private final SchemaFactory<ReviewDb> schema;
  private boolean finished = false;

  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 1024;
  // Queue of events to replicate
  private final LinkedBlockingQueue<ChangeEvent> queue =   new LinkedBlockingQueue<>(MAX_EVENTS);
  private final Object replicationLock = new Object();
  private static Replicator replicatorInstance = null;

  public static synchronized ReplicatedEventsManager hookOnListeners(ChangeHookRunner changeHookRunner,
      final SchemaFactory<ReviewDb> schema, Config config) {

    log.info("RE ReplicatedEvents hook called...");
    Replicator.setGerritConfig(config);

    if (internalLogEnabled) {
      internalLogFile = new File(new File(DAFAULT_BASE_DIR),"replEvents.log"); // used for debug
    }
    logMe("ReplicatedEvents hook called, with "+changeHookRunner+" set",null);

    boolean configOk = readConfiguration();
    log.info("RE Configuration read: ok? {}, replicatedEvent are enabled? {}",new Object[] {configOk,replicatedEventsEnabled});

    if (replicatedEventsEnabled) {
      replicatorInstance = Replicator.getInstance();

      if (eventReaderAndPublisherThread == null) {
        instance = new ReplicatedEventsManager(changeHookRunner,schema);
        Replicator.subscribeEvent(ChangeEventWrapper.Originator.GERRIT_EVENT, instance);

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
        Replicator.unsubscribeEvent(ChangeEventWrapper.Originator.GERRIT_EVENT, instance);
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
        if (localRepublishEnabled) {
          try {
            publishIncomingReplicatedEvents(makeDistinct(event, distinctEventPrefix));
          } catch(ClassNotFoundException e) {
            log.error("RE local republished event has been lost.",e);
          }
        }
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
          } else {
            finished = true;
            throw new IllegalStateException("RE Replicated events are not enabled.");
          }
        }
      } catch (InterruptedException e) {
        log.info("RE Exiting",e);
        finished = true;
      } catch(RuntimeException  e ) {
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
      replicatorInstance.queueEventForReplication(new ChangeEventWrapper(newEvent,getChangeEventInfo(newEvent),distinctEventPrefix));
      eventGot = true;
    }
    return eventGot;
  }

  /**
   * This is the function implementing the Replicator.GerritPublishable interface
   * aimed at receiving the event to be published
   *
   * @param newEvent
   * @return
   */
  @Override
  public boolean publishIncomingReplicatedEvents(ChangeEventWrapper newEvent) {
    boolean result = true;
    if (receiveReplicatedEventsEnabled) {
      try {
        Class<?> eventClass = Class.forName(newEvent.className);
        ChangeEvent originalEvent = (ChangeEvent) gson.fromJson(newEvent.changeEvent, eventClass);
        log.debug("RE Original event: {}",originalEvent.toString());
        originalEvent.replicated = true;

        if (replicatedEventsReplicateOriginalEvents) {
          if(!publishIncomingReplicatedEvents(originalEvent)) {
            log.error("RE event has been lost, not supported");
            result = false;
          }
        }

        if (replicatedEventsReplicateDistinctEvents) {
          if (!publishIncomingReplicatedEvents(makeDistinct(originalEvent,newEvent.prefix))) {
            log.error("RE distinct event has been lost, not supported");
            result = false;
          }
        }

      } catch(ClassNotFoundException e) {
        log.error("RE event has been lost. Could not find {}",newEvent.className,e);
        result = false;
      }
    }
    return result;
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
        log.error("RE While trying to publish a replicated event", e);
      } finally {
        // since it's a schema.open() call to get the db, we are closing it
        if (db != null) {
          db.close();
        }
      }
    }
    return changeEventInfo.supported;
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
   * Creates a new ChangeEvent based on an original ChangeEvent, but with a new type which is
   * derived from the original type, with a new prefix.
   * 
   * @param changeEvent
   * @param prefix
   * @return
   * @throws ClassNotFoundException 
   */
  @SuppressWarnings("UseSpecificCatch")
  private ChangeEvent makeDistinct(ChangeEvent changeEvent, String prefix) throws ClassNotFoundException {
    
    ChangeEvent result = null;
    Class<? extends ChangeEvent> actualClass = changeEvent.getClass();
    
    try {
      Constructor<? extends ChangeEvent> constructor = actualClass.getConstructor(actualClass, String.class, boolean.class);
      result = constructor.newInstance(changeEvent,prefix+changeEvent.getType(),true);
    } catch (RuntimeException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
      ClassNotFoundException c = new ClassNotFoundException("Error while creating distinct event",e);
      log.error("RE Could not rebuild distinct event",c);
      throw c;
    }
    return result;
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

      if(applicationProperties.exists() && applicationProperties.canRead()) {
        Properties props = new Properties();
        try (FileInputStream propsFile = new FileInputStream(applicationProperties)) {
          props.load(propsFile);
          replicatedEventsSend = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_SEND,"true"));
          replicatedEventsReceive = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE,"true"));
          replicatedEventsReplicateOriginalEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL,"true"));
          replicatedEventsReplicateDistinctEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT,"false"));

          receiveReplicatedEventsEnabled = replicatedEventsReceive || replicatedEventsReplicateOriginalEvents || replicatedEventsReplicateDistinctEvents;
          replicatedEventsEnabled = receiveReplicatedEventsEnabled || replicatedEventsSend;
          if (replicatedEventsEnabled) {
            maxSecsToWaitForEventOnQueue = Long.parseLong(
                cleanLforLong(props.getProperty(GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE,DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE)));
            log.info("RE Replicated events are enabled, send: {}, receive: {}", new Object[]{replicatedEventsSend,receiveReplicatedEventsEnabled});
          } else {
            log.info("RE Replicated events are disabled"); // This could not apppear in the log... cause the log could not yet be ready
          }

          localRepublishEnabled = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT,"false"));
          distinctEventPrefix = props.getProperty(GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX, DEFAULT_DISTINCT_PREFIX);
          
          log.info("RE Replicated events: receive={}, original={}, distinct={}, send={} ",
              new Object[]{replicatedEventsReceive,replicatedEventsReplicateOriginalEvents,replicatedEventsReplicateDistinctEvents, replicatedEventsSend});
          log.info("RE Replicate local events, prefix={}, republish={} ", new Object[]{distinctEventPrefix,localRepublishEnabled});

          result = true;
        } catch(IOException e) {
          log.error("RE While reading GerritMS properties file",e);
        }
      }
    } catch(IOException ee) {
      log.error("RE While loading the .gitconfig file",ee);
    }
    return result;
  }

  private static String cleanLforLong(String property) {
    if (property != null && property.length() > 1 &&  (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0,property.length()-1);
    }
    return property;
  }

  @Override
  public int hashCode() {
    int hash = 5;
    hash = 73 * hash + (this.finished ? 1 : 0);
    hash = 73 * hash + Objects.hashCode(this.queue);
    hash = 73 * hash + Objects.hashCode(this.replicationLock);
    hash = 73 * hash + Objects.hashCode(this.listener);
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
    final ReplicatedEventsManager other = (ReplicatedEventsManager) obj;
    if (this.finished != other.finished) {
      return false;
    }
    if (!Objects.equals(this.queue, other.queue)) {
      return false;
    }
    if (!Objects.equals(this.replicationLock, other.replicationLock)) {
      return false;
    }
    return Objects.equals(this.listener, other.listener);
  }

}
