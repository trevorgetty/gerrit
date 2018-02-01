/**
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 *
 * http://www.wandisco.com/
 */
package com.google.gerrit.common;

import com.google.common.base.Supplier;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.ChangeRestoredEvent;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.DraftPublishedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.events.ReviewerAddedEvent;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gerrit.server.events.TopicChangedEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
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
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL = "gerrit.replicated.events.enabled.receive.original";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT = "gerrit.replicated.events.enabled.receive.distinct";
  public static final String GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT = "gerrit.replicated.events.enabled.local.republish.distinct";
  public static final String GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX = "gerrit.replicated.events.distinct.prefix";
  public static final String DEFAULT_DISTINCT_PREFIX = "REPL-";
  public static final String GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "gerrit.replicated.events.secs.on.queue";
  public static final String ENC = "UTF-8"; // From BaseCommand
  public static final String DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "5";
  public static final String EVENTS_REPLICATION_THREAD_NAME = "EventsStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");

  public static final boolean internalLogEnabled = false;
  private static long maxSecsToWaitForEventOnQueue;
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
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();
  private EventBroker changeHookRunner = null;
  private final SchemaFactory<ReviewDb> schemaFactory;

  private boolean finished = false;

  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 1024;
  // Queue of events to replicate
  private final LinkedBlockingQueue<Event> queue =   new LinkedBlockingQueue<>(MAX_EVENTS);
  private final Object replicationLock = new Object();
  private static Replicator replicatorInstance = null;
  private static Config cfg;

  public static synchronized ReplicatedEventsManager hookOnListeners(EventBroker changeHookRunner, SchemaFactory<ReviewDb> schemaFactory, Config config) {

    log.info("RE ReplicatedEvents hook called...");

    Replicator.setGerritConfig(config==null? new Config():config);
    cfg = config;

    if (internalLogEnabled) {
      internalLogFile = new File(new File(DEFAULT_BASE_DIR),"replEvents.log"); // used for debug
      System.err.println("LOG FILE: "+internalLogFile);
    }
    logMe("ReplicatedEvents hook called, with "+changeHookRunner+" set",null);

    boolean configOk = readConfiguration();
    log.info("RE Configuration read: ok? {}, replicatedEvent are enabled? {}",new Object[] {configOk,replicatedEventsEnabled});

    if (replicatedEventsEnabled) {
      replicatorInstance = Replicator.getInstance();

      if (eventReaderAndPublisherThread == null) {
        instance = new ReplicatedEventsManager(changeHookRunner, schemaFactory);
        Replicator.subscribeEvent(EventWrapper.Originator.GERRIT_EVENT, instance);

        eventReaderAndPublisherThread = new Thread(instance);
        eventReaderAndPublisherThread.setName(EVENTS_REPLICATION_THREAD_NAME);
        eventReaderAndPublisherThread.start();
        log.info("RE ReplicatedEvents instance added");
        logMe("ReplicatedEvents instance added",null);

        changeHookRunner.unrestrictedListeners.add(instance.listener);
        log.info("ReplicatedEvents change listener added");
      } else {
        log.error("RE Thread {} is already running!",EVENTS_REPLICATION_THREAD_NAME);
        logMe("Thread "+EVENTS_REPLICATION_THREAD_NAME+" is already running!",null);
      }
    } else {
      if (instance != null) {
        instance.finished = true;
        Replicator.unsubscribeEvent(EventWrapper.Originator.GERRIT_EVENT, instance);
      }
      instance = null;
    }
    return instance;
  }

  public synchronized void setGerritConfig(Config config) {
    cfg = config;
  }

  public static synchronized Config getGerritConfig() {
    return cfg;
  }

  private ReplicatedEventsManager(EventBroker changeHookRunner, final SchemaFactory<ReviewDb> schemaFactory) {
    this.changeHookRunner=changeHookRunner;
    this.schemaFactory = schemaFactory;
    log.info("RE ReplicatedEvents instance added");
    logMe("ReplicatedEvents instance added",null);
  }

  private ReplicatedEventsManager() {
    schemaFactory = null;
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

  public final EventListener listener = new EventListener() {

    @Override
    public void onEvent(Event event) {
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

  private void offer(final Event event) {
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
   * read by the replicator. Using milliseconds so that the user can specify sub second
   * periods
   */
  private boolean pollAndWriteOutgoingEvents() throws InterruptedException {
    boolean eventGot = false;
    Event newEvent;
    newEvent = queue.poll(maxSecsToWaitForEventOnQueue, TimeUnit.MILLISECONDS);
    if (newEvent != null && !newEvent.replicated) {
      replicatorInstance.queueEventForReplication(new EventWrapper(newEvent,getChangeEventInfo(newEvent),distinctEventPrefix));
      eventGot = true;
    }
    return eventGot;
  }

  /**
   * This is the function implementing the Replicator.GerritPublishable interface
   * aimed at receiving the event to be published
   *
   * @param newEvent
   * @return result
   */
  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean result = true;
    if (receiveReplicatedEventsEnabled) {
      try {
        Class<?> eventClass = Class.forName(newEvent.className);
        Event originalEvent = (Event) gson.fromJson(newEvent.event, eventClass);

        if (originalEvent == null) {
          log.error("fromJson method returned null for {}", newEvent.toString());
          return false;
        }

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
        log.error("RE event has been lost. Could not find {} for event {}",newEvent.className, newEvent, e);
        result = false;
      } catch (JsonSyntaxException je) {
        log.error("PR Could not decode json event {}", newEvent.toString(), je);
        return result;
      } catch(RuntimeException e) {
        log.error("RE event has been lost. Could not rebuild event for {}", newEvent, e);
        result = false;
      }

    }
    return result;
  }

  /**
   * Publishes the event calling the postEvent function in ChangeHookRunner
   *
   * @param newEvent
   * @return result
   */
  private boolean publishIncomingReplicatedEvents(Event newEvent) {
    ChangeEventInfo changeEventInfo = getChangeEventInfo(newEvent);

    if (changeEventInfo.isSupported()) {
      log.debug("RE going to fire event...");

      try(ReviewDb db = schemaFactory.open()) {
        if (changeEventInfo.changeAttr != null) {
          log.debug("RE using changeAttr: {}...", changeEventInfo.changeAttr);
          Change change = db.changes().get(Change.Id.parse(changeEventInfo.changeAttr.number));
          log.debug("RE got change from db: {}", change);
          changeHookRunner.postEvent(change, (ChangeEvent) newEvent);
        } else if (changeEventInfo.branchName != null) {
          log.debug("RE using branchName: {}", changeEventInfo.branchName);
          changeHookRunner.postEvent(changeEventInfo.branchName, (RefEvent) newEvent);
        } else if (newEvent instanceof ProjectCreatedEvent) {
          changeHookRunner.postEvent(((ProjectCreatedEvent) newEvent));
        } else {
          log.error("RE Internal error, it's *supported*, but refs is null",new Exception("refs is null for supported event"));
          changeEventInfo.supported = false;
        }
      } catch (OrmException e) {
        log.error("RE While trying to publish a replicated event", e);
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
  public ChangeEventInfo getChangeEventInfo(Event newEvent) {
    ChangeEventInfo changeEventInfo = new ChangeEventInfo();
    if (newEvent instanceof com.google.gerrit.server.events.ChangeAbandonedEvent) {
      changeEventInfo.setChangeAttribute(((ChangeAbandonedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeMergedEvent) {
      changeEventInfo.setChangeAttribute(((ChangeMergedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeRestoredEvent) {
      changeEventInfo.setChangeAttribute(((ChangeRestoredEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.CommentAddedEvent) {
      changeEventInfo.setChangeAttribute(((CommentAddedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.CommitReceivedEvent) {
      CommitReceivedEvent event = (CommitReceivedEvent) newEvent;
      changeEventInfo.setProjectName(event.project.getName());
      changeEventInfo.setBranchName(new Branch.NameKey(event.project.getNameKey(), completeRef(event.refName)));
    } else if (newEvent instanceof com.google.gerrit.server.events.DraftPublishedEvent) {
      changeEventInfo.setChangeAttribute(((DraftPublishedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.PatchSetCreatedEvent) {
      changeEventInfo.setChangeAttribute(((PatchSetCreatedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.RefUpdatedEvent) {
      RefUpdatedEvent event = (RefUpdatedEvent) newEvent;
      if (event.refUpdate != null) {
        changeEventInfo.setProjectName(event.refUpdate.get().project);
        changeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(event.refUpdate.get().project), completeRef(event.refUpdate.get().refName)));
      } else {
        log.error("RE {} is not supported, project name or refupdate is null!", newEvent.getClass().getName());
        changeEventInfo.supported = false;
      }
    } else if (newEvent instanceof com.google.gerrit.server.events.ReviewerAddedEvent) {
      changeEventInfo.setChangeAttribute(((ReviewerAddedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.TopicChangedEvent) {
      changeEventInfo.setChangeAttribute(((TopicChangedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ProjectCreatedEvent) {
      changeEventInfo.setProjectName(((ProjectCreatedEvent) newEvent).projectName);
    } else {
      log.error("RE "+newEvent.getClass().getName()+" is not supported!", new IllegalArgumentException("RE Does this event needs management?"));
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
   * @return result
   * @throws ClassNotFoundException
   */
  @SuppressWarnings("UseSpecificCatch")
  private Event makeDistinct(Event changeEvent, String prefix) throws ClassNotFoundException {

    Event result = null;
    Class<? extends Event> actualClass = changeEvent.getClass();

    try {
      Constructor<? extends Event> constructor = actualClass.getConstructor(actualClass, String.class, boolean.class);
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
      this.supported = true;
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
          replicatedEventsSend = true; // they must be always enabled, not dependant on GERRIT_REPLICATED_EVENTS_ENABLED_SEND
          replicatedEventsReceive = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE,"true"));
          replicatedEventsReplicateOriginalEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL,"true"));
          replicatedEventsReplicateDistinctEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT,"false"));

          receiveReplicatedEventsEnabled = replicatedEventsReceive || replicatedEventsReplicateOriginalEvents || replicatedEventsReplicateDistinctEvents;
          replicatedEventsEnabled = receiveReplicatedEventsEnabled || replicatedEventsSend;
          if (replicatedEventsEnabled) {
            maxSecsToWaitForEventOnQueue = Long.parseLong(Replicator.cleanLforLongAndConvertToMilliseconds(props.getProperty(
                GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE,DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE)));
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
