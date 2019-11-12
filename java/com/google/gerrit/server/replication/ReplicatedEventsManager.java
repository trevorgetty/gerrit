
/**
 * Copyright (c) 2014-2018 WANdisco
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Apache License, Version 2.0
 * <p>
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 * <p>
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 * <p>
 * http://www.wandisco.com/
 */

/**
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 *
 * http://www.wandisco.com/
 */
package com.google.gerrit.server.replication;

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.*;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;

import com.google.inject.Provider;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.google.gerrit.server.replication.ReplicationConstants.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 *
 * @author antoniochirizzi
 */
public final class ReplicatedEventsManager implements Runnable, Replicator.GerritPublishable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");

  public static final boolean internalLogEnabled = false;
  private static long maxSecsToWaitForEventOnQueue;
  private static Thread eventReaderAndPublisherThread = null;
  private static File internalLogFile = null; // used for debug
  private static String distinctEventPrefix = DEFAULT_DISTINCT_PREFIX;
  private static boolean replicatedEventsReceive = true; // receive and original are synonym
  private static boolean replicatedEventsReplicateOriginalEvents = true; // receive and original are synonym
  private static boolean replicatedEventsReplicateDistinctEvents = false;
  private static boolean receiveReplicatedEventsEnabled = true;
  private static boolean replicatedEventsEnabled = true;
  private static boolean replicatedEventsSend = true;
  private static boolean localRepublishEnabled = false;
  private static ReplicatedEventsManager instance = null;
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();
  private final EventBroker changeHookRunner;
  private final Provider<ReviewDb> dbProvider;

  private boolean finished = false;

  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 1024;
  // Queue of events to replicate
  private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(MAX_EVENTS);
  private final Object replicationLock = new Object();
  private static Replicator replicatorInstance = null;
  private Config cfg;
  private static final String nonReplicatedEventMessage = "Error while creating distinct event, this is likely a non replicated event, skipping.";

  public static synchronized ReplicatedEventsManager hookOnListeners(EventBroker changeHookRunner, Config config) {

    logger.atInfo().log("RE ReplicatedEvents hook called...");

    Replicator.setGerritConfig(config == null ? new Config() : config);

    if (internalLogEnabled) {
      internalLogFile = new File(new File(DEFAULT_BASE_DIR), "replEvents.log"); // used for debug
      System.err.println("LOG FILE: " + internalLogFile);
    }
    logMe("ReplicatedEvents hook called, with " + changeHookRunner + " set", null);

    boolean configOk = readConfiguration();
    logger.atInfo().log("RE Configuration read: ok? %s, replicatedEvent are enabled? %s", configOk, replicatedEventsEnabled);

    if (replicatedEventsEnabled) {
      if (replicatorInstance == null) {
        replicatorInstance = Replicator.getInstance();
      }

      if (eventReaderAndPublisherThread == null) {
        instance = new ReplicatedEventsManager(changeHookRunner);
        Replicator.subscribeEvent(EventWrapper.Originator.GERRIT_EVENT, instance);

        eventReaderAndPublisherThread = new Thread(instance);
        eventReaderAndPublisherThread.setName(EVENTS_REPLICATION_THREAD_NAME);
        eventReaderAndPublisherThread.start();
        logger.atInfo().log("RE ReplicatedEvents instance added");
        logMe("ReplicatedEvents instance added", null);

        changeHookRunner.registerUnrestrictedEventListener(EVENTS_REPLICATION_THREAD_NAME, instance.listener);
        logger.atInfo().log("ReplicatedEvents change listener added");
      } else {
        logger.atSevere().log("RE Thread %s is already running!", EVENTS_REPLICATION_THREAD_NAME);
        logMe("Thread " + EVENTS_REPLICATION_THREAD_NAME + " is already running!", null);
      }

      return instance;
    }

    // Gerrit events are disabled if we are moving from enabled to disabled / unhook existing event listeners.
    if (instance != null) {
      instance.finished = true;
      Replicator.unsubscribeEvent(EventWrapper.Originator.GERRIT_EVENT, instance);
    }
    instance = null;
    return null;
  }

  private ReplicatedEventsManager(EventBroker changeHookRunner) {
    this.changeHookRunner = changeHookRunner;
    this.dbProvider = changeHookRunner.getDbProvider();

    logger.atInfo().log("RE ReplicatedEvents instance added");
    logMe("ReplicatedEvents instance added", null);
  }

  private ReplicatedEventsManager() {
    changeHookRunner = null;
    dbProvider = null;
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

  public final EventListener listener = new EventListener() {

    @Override
    public void onEvent(Event event) {
      if (!event.replicated) {
        offer(event);
        if (localRepublishEnabled) {
          try {
            publishIncomingReplicatedEvents(makeDistinct(event, distinctEventPrefix));
          } // Just log info level with no stack trace so we don't spam the logs for
          // the following catch blocks
          catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            // log out full stacktrace in debug mode, but leave rest as info, user friendly,
            // although I hope we do not hit this any longer as we could have failed the local event somehow?
            logger.atInfo().log("%s : %s : %s", e.getClass().getName(), e.getMessage(), nonReplicatedEventMessage);
            logger.atFiner().withCause(e).log("publicIncomingReplicatedEvents failure details: ");
          } catch (RuntimeException e) {
            logger.atInfo().log("%s : %s : %s", e.getClass().getName(), e.getMessage(), nonReplicatedEventMessage);
            logger.atFiner().withCause(e).log("publicIncomingReplicatedEvents failure details: ");
          }
        }
      }
    }
  };


  private void offer(final Event event) {
    synchronized (replicationLock) {
      if (!queue.offer(event)) {
        logger.atSevere().withCause(new Exception("Cannot offer event to queue")).log(
            "RE Could not queue an event! (The event will not be replicated)");
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
    logger.atInfo().log("RE ReplicateEvents thread is starting...");
    logMe("ReplicateEvents thread is starting...", null);

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
    logger.atSevere().log("RE Thread finished");
    logMe("Thread finished", null);
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
      newEvent.setNodeIdentity(replicatorInstance.getThisNodeIdentity());
      replicatorInstance.queueEventForReplication(new EventWrapper(newEvent, getChangeEventInfo(newEvent), distinctEventPrefix));
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
          logger.atSevere().log("fromJson method returned null for %s", newEvent.toString());
          return false;
        }

        logger.atFiner().log("RE Original event: %s", originalEvent.toString());
        originalEvent.replicated = true;
        originalEvent.setNodeIdentity(replicatorInstance.getThisNodeIdentity());

        if (replicatedEventsReplicateOriginalEvents) {
          if (!publishIncomingReplicatedEvents(originalEvent)) {
            logger.atSevere().log("RE event has been lost, not supported");
            result = false;
          }
        }

        if (replicatedEventsReplicateDistinctEvents) {
          if (!publishIncomingReplicatedEvents(makeDistinct(originalEvent, newEvent.prefix))) {
            logger.atSevere().log("RE distinct event has been lost, not supported");
            result = false;
          }
        }

      } catch (JsonSyntaxException e) {
        logger.atSevere().withCause(e).log("PR Could not decode json event %s", newEvent.toString());
        return result;
      } // Just log info level with no stack trace so we don't spam the logs for
      // the following catch blocks
      catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
        logger.atInfo().log("%s : %s : %s", e.getClass().getName(), e.getMessage(), nonReplicatedEventMessage);
        result = false;
      } catch (RuntimeException e) {
        logger.atInfo().log("%s : %s : %s", e.getClass().getName(), e.getMessage(), nonReplicatedEventMessage);
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
      logger.atFiner().log("RE going to fire event...");

      try (ReviewDb db = dbProvider.get()) {
        if (changeEventInfo.changeAttr != null) {
          logger.atFiner().log("RE using changeAttr: %s...", changeEventInfo.changeAttr);
          Change change = db.changes().get(new Change.Id(changeEventInfo.changeAttr.number));
          logger.atFiner().log("RE got change from db: %s", change);
          changeHookRunner.postEvent(change, (ChangeEvent) newEvent);
        } else if (changeEventInfo.branchName != null) {
          logger.atFiner().log("RE using branchName: %s", changeEventInfo.branchName);
          changeHookRunner.postEvent(changeEventInfo.branchName, (RefEvent) newEvent);
        } else if (newEvent instanceof ProjectCreatedEvent) {
          changeHookRunner.postEvent(((ProjectCreatedEvent) newEvent));
        } else {
          logger.atSevere().withCause(new Exception("refs is null for supported event")).log(
              "RE Internal error, it's *supported*, but refs is null");
          changeEventInfo.supported = false;
        }
      } catch (OrmException | PermissionBackendException e) {
        logger.atSevere().withCause(e).log("RE While trying to publish a replicated event");
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
    } else if (newEvent instanceof com.google.gerrit.server.events.PatchSetCreatedEvent) {
      changeEventInfo.setChangeAttribute(((PatchSetCreatedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.RefUpdatedEvent) {
      RefUpdatedEvent event = (RefUpdatedEvent) newEvent;
      if (event.refUpdate != null) {
        changeEventInfo.setProjectName(event.refUpdate.get().project);
        changeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(event.refUpdate.get().project), completeRef(event.refUpdate.get().refName)));
      } else {
        logger.atInfo().log("RE %s is not supported, project name or refupdate is null!", newEvent.getClass().getName());
        changeEventInfo.supported = false;
      }
    } else if (newEvent instanceof com.google.gerrit.server.events.ReviewerAddedEvent) {
      changeEventInfo.setChangeAttribute(((ReviewerAddedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.TopicChangedEvent) {
      changeEventInfo.setChangeAttribute(((TopicChangedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ProjectCreatedEvent) {
      changeEventInfo.setProjectName(((ProjectCreatedEvent) newEvent).projectName);
    } else {
      logger.atInfo().log("RE %s is not supported!", newEvent.getClass().getName());
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
   * @throws NoSuchMethodException, InstantiationException, InvocationTargetException, RuntimeException, IllegalAccessException
   */
  private Event makeDistinct(Event changeEvent, String prefix) throws NoSuchMethodException, InstantiationException,
      InvocationTargetException, RuntimeException, IllegalAccessException {

    Event result = null;
    Class<? extends Event> actualClass = changeEvent.getClass();

    try {
      Constructor<? extends Event> constructor = actualClass.getConstructor(actualClass, String.class, boolean.class);
      result = constructor.newInstance(changeEvent, prefix + changeEvent.getType(), true);
      result.setNodeIdentity(replicatorInstance.getThisNodeIdentity());
    } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
      // this is likely a non replicated event
      logger.atFiner().withCause(e).log(nonReplicatedEventMessage);
      throw e;
    } catch (RuntimeException e) {
      // this is likely a non replicated event
      logger.atFiner().withCause(e).log(nonReplicatedEventMessage);
      throw e;
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
      return "refs/heads/" + refName;
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
      Properties props = Replicator.getApplicationProperties();

      // we allow no properties to be returned when the replication is disabled in an override
      if (props == null) {
        return false;
      }

      replicatedEventsSend = true; // they must be always enabled, not dependant on GERRIT_REPLICATED_EVENTS_ENABLED_SEND
      replicatedEventsReceive = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE, "true"));
      replicatedEventsReplicateOriginalEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL, "true"));
      replicatedEventsReplicateDistinctEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_DISTINCT, "false"));

      receiveReplicatedEventsEnabled = replicatedEventsReceive || replicatedEventsReplicateOriginalEvents || replicatedEventsReplicateDistinctEvents;
      replicatedEventsEnabled = receiveReplicatedEventsEnabled || replicatedEventsSend;
      if (replicatedEventsEnabled) {
        maxSecsToWaitForEventOnQueue = Long.parseLong(Replicator.cleanLforLongAndConvertToMilliseconds(props.getProperty(
            GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE, DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE)));
        logger.atInfo().log("RE Replicated events are enabled, send: %s, receive: %s", replicatedEventsSend, receiveReplicatedEventsEnabled);
      } else {
        logger.atInfo().log("RE Replicated events are disabled"); // This could not apppear in the log... cause the log could not yet be ready
      }

      localRepublishEnabled = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_LOCAL_REPUBLISH_DISTINCT, "false"));
      distinctEventPrefix = props.getProperty(GERRIT_REPLICATED_EVENTS_DISTINCT_PREFIX, DEFAULT_DISTINCT_PREFIX);

      logger.atInfo().log("RE Replicated events: receive=%s, original=%s, distinct=%s, send=%s ",
          replicatedEventsReceive, replicatedEventsReplicateOriginalEvents, replicatedEventsReplicateDistinctEvents, replicatedEventsSend);
      logger.atInfo().log("RE Replicate local events, prefix=%s, republish=%s ", distinctEventPrefix, localRepublishEnabled);

      return true;
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("RE While reading GerritMS properties file");
    }
    return false;
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
