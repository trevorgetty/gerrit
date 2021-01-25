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

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.ChangeDeletedEvent;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventBroker;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.EventTypes;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.events.SkipReplication;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import static com.google.gerrit.server.replication.ReplicationConstants.EVENTS_REPLICATION_THREAD_NAME;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.GERRIT_EVENT;

import javax.inject.Singleton;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

@Singleton
public class ReplicatedEventsWorker implements Runnable, Replicator.GerritPublishable {

  private final ReplicatedEventsManager replicatedEventsManager;

  // start out as if we are finished or not in use... only valid construction will enable
  // us to run.
  private boolean finished = true;
  private EventBroker changeHookRunner;

  // Maximum number of events that may be queued up
  private static final int MAX_EVENTS = 1024;

  // Queue of events to replicate
  private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(MAX_EVENTS);
  private final Object replicationLock = new Object();

  private static Replicator replicatorInstance = null;
  private static final String errorReplicatedEventMessage = "Error while trying to replicate event: %s";
  private static Thread eventReaderAndPublisherThread = null;
  private static FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();

  public ReplicatedEventsWorker(
      ReplicatedEventsManager replicatedEventsManager,
      EventBroker eventBroker
  ) {
    this.replicatedEventsManager = replicatedEventsManager;
    this.changeHookRunner = eventBroker;
  }

  public synchronized void startReplicationThread() {
    // hook in our event broker and start the instance running!
    if (!replicatedEventsManager.isReplicatedEventsEnabled()) {
      // Gerrit events are disabled if we are moving from enabled to disabled / unhook existing event listeners.
      stopReplicationThread();
      return;
    }

    if (eventReaderAndPublisherThread == null) {
      replicatorInstance = Replicator.getInstance();
      Replicator.subscribeEvent(GERRIT_EVENT, this);
      // initialize our state, so we can use this to signal shutdown / finish.
      finished = false;
      // Passed in via lifecycle manager, to avoid a cyclic dependency in eventbroker->repEventManager->eventBroker
      eventReaderAndPublisherThread = new Thread(this);
      eventReaderAndPublisherThread.setName(EVENTS_REPLICATION_THREAD_NAME);
      eventReaderAndPublisherThread.start();
      logger.atInfo().log("RE ReplicatedEvents instance added");
      logMe("ReplicatedEvents instance added", null);
    } else {
      logger.atSevere().log("RE Thread %s is already running!", EVENTS_REPLICATION_THREAD_NAME);
      logMe("Thread " + EVENTS_REPLICATION_THREAD_NAME + " is already running!", null);
    }
  }

  public synchronized void stopReplicationThread() {
    if (!finished) {
      finished = true;
      Replicator.unsubscribeEvent(GERRIT_EVENT, this);
    }
  }

  private static synchronized void clearThread() {
    eventReaderAndPublisherThread = null;
  }

  /**
   * This functions is just to log something when the Gerrit logger is not yet available
   * and you need to know if it's working. To be used for debugging purposes.
   * Gerrit will not log anything until the log system will be initialized.
   *
   * @param msg
   * @param t
   */
  void logMe(String msg, Throwable t) {
    if (!replicatedEventsManager.isInternalLogEnabled()) {
      return;
    }

    try (PrintWriter p = new PrintWriter(Files.newBufferedWriter(replicatedEventsManager.getInternalLogFile().toPath(), UTF_8, CREATE, APPEND))) {
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
      //Only offer the event to the queue if it is not a skipped event.
      if (!isEventToBeSkipped(event)) {
        offer(event);
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
   * <p>
   * After this or while this happens, the thread will also look for files
   * coming from the replicator, which need to be read and published as they
   * are incoming events.
   */
  @Override
  public void run() {

    logger.atInfo().log("RE ReplicateEvents thread is starting...");
    logMe("ReplicateEvents thread is starting...", null);

    changeHookRunner.registerUnrestrictedEventListener(EVENTS_REPLICATION_THREAD_NAME, listener);
    logger.atInfo().log("ReplicatedEvents change listener added");

    // we need to make this thread never fail, otherwise we'll lose events.
    while (!finished) {
      try {
        while (true) {
          if (replicatedEventsManager.isReplicatedEventsSend()) {
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
    newEvent = queue.poll(replicatedEventsManager.getMaxSecsToWaitForEventOnQueue(),
        TimeUnit.MILLISECONDS);
    //If the newEvent is not a skipped event then we should queue the event for replication.
    if (newEvent != null && !isEventToBeSkipped(newEvent)) {
      replicatorInstance.queueEventForReplication(
          GerritEventFactory.createReplicatedChangeEvent(newEvent, getChangeEventInfo(newEvent)));
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
    if (replicatedEventsManager.isReplicatedEventsEnabled()) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        Event originalEvent = (Event) gson.fromJson(newEvent.getEvent(), eventClass);

        if (originalEvent == null) {
          logger.atSevere().log("fromJson method returned null for %s", newEvent.toString());
          return false;
        }

        logger.atFine().log("RE Original event: %s", originalEvent.toString());
        // All events we read in are replicated events.
        originalEvent.hasBeenReplicated = true;

        if (replicatedEventsManager.isReplicatedEventsReplicateOriginalEvents()) {
          if (!publishIncomingReplicatedEventsLocalImpl(originalEvent)) {
            logger.atSevere().log("RE event has been lost, not supported");
            result = false;
          }
        }

      } catch (JsonSyntaxException e) {
        logger.atSevere().withCause(e).log("PR Could not decode json event %s", newEvent.toString());
        return result;
      } catch (ClassNotFoundException e) {
        logger.atInfo().log(errorReplicatedEventMessage, newEvent.getEvent());
        logger.atFine().withCause(e).log(errorReplicatedEventMessage, newEvent.getEvent());
        result = false;
      } catch (RuntimeException e) {
        logger.atSevere().withCause(e).log(errorReplicatedEventMessage, newEvent.getEvent());
        result = false;
      }

    }
    return result;
  }

  /**
   * Publishes the event calling the postEvent function in ChangeHookRunner, which calls
   * any listener ( any plugin context which wants to hear this raised event ).
   *
   * @param newEvent
   * @return result
   */
  private boolean publishIncomingReplicatedEventsLocalImpl(Event newEvent) {
    ReplicatedChangeEventInfo changeEventInfo = getChangeEventInfo(newEvent);

    if (changeEventInfo.isSupported()) {
      logger.atFine().log("RE going to fire event...");

      try (ReviewDb db = replicatedEventsManager.getReviewDbProvider().get()) {
        if (changeEventInfo.getChangeAttr() != null) {
          logger.atFine().log("RE using changeAttr: %s...", changeEventInfo.getChangeAttr());
          if (newEvent instanceof ChangeDeletedEvent){
            //changeDeletedEvent has changeAttr needs fired without look up as it is a delete
            // fired through generic event path to avoid Change look up
            changeHookRunner.postEvent(newEvent);
            return true;
          }
          // As this is a replicated event, and we are raising to listeners create without auto rebuilding of the
          // index, this stops us potentially rebuilding the index twice.  If we raise a changed event, we later
          // check for reindexing again which could rebuild a missing index (used during online migration only )
          ChangeNotes changeNotes = replicatedEventsManager.getChangeNotesFactory().createWithAutoRebuildingDisabled(
                  db, new Project.NameKey(changeEventInfo.getProjectName()), new Change.Id(changeEventInfo.getChangeAttr().number));
          Change change = changeNotes.getChange();
          logger.atFine().log("RE got change from db: %s", change);
          changeHookRunner.postEvent(change, (ChangeEvent) newEvent);
        } else if (changeEventInfo.getBranchName() != null) {
          logger.atFine().log("RE using branchName: %s", changeEventInfo.getBranchName());
          changeHookRunner.postEvent(changeEventInfo.getBranchName(), (RefEvent) newEvent);
        } else if (newEvent instanceof ProjectCreatedEvent) {
          changeHookRunner.postEvent(((ProjectCreatedEvent) newEvent).getProjectNameKey(),
                                     ((ProjectCreatedEvent) newEvent));
        } else {
          logger.atSevere().withCause(new Exception("refs is null for supported event")).log(
              "RE Internal error, it's *supported*, but refs is null");
          changeEventInfo.setSupported(false);
        }
      } catch (OrmException | PermissionBackendException e) {
        logger.atSevere().withCause(e).log("RE While trying to publish a replicated event");
      }
    }
    return changeEventInfo.isSupported();
  }

  /**
   * isEventToBeSkipped uses 3 things.
   * 1) has the event previously been replicated - if so we dont do it again!!
   * 2) IS the event in a list of events we are not to replicate ( a skip list )
   * 3) Is the event annotated with the @SkipReplication annotation, if it is, skip it.
   *    Using the SkipReplication annotation should be used with caution as their are normally
   *    multiple events associated with a given operation in Gerrit and skipping one could
   *    leave the repository in a bad state.
   *
   * @param event
   * @return
   */
  public boolean isEventToBeSkipped(Event event) {
    if (event.hasBeenReplicated) {
      // dont cause cyclic loop replicating forever./
      return true;
    }

    //If the event contains a skipReplication annotation then we skip the event
    Class eventClass = EventTypes.getClass(event.type);

    if(eventClass.isAnnotationPresent(SkipReplication.class)){
      return true;
    }

    return isEventInSkipList(event);
  }

  /**
   * This checks against the list of event class names to be skipped
   * Skippable events are configured by a parameter in the application.properties
   * as a comma separated list of class names for event types, e.g
   * TopicChangedEvent, ReviewerDeletedEvent.
   *
   * @param event
   * @return
   */
  public boolean isEventInSkipList(Event event) {
    //Doesn't matter if the list is empty, check if the list contains the class name.
    //All events are stored in the list as lowercase, so we check for our lowercase class name.
    return replicatedEventsManager.getEventSkipList().contains(event.getClass().getSimpleName().toLowerCase()); //short name of the class
  }

  /**
   * Since the event can be of many different types, and since the Gerrit engineers didn't want
   * to put the ChangeAttribute in the main abstract class, we have to analyze every
   * single event type and extract the relevant information
   *
   * @param newEvent
   * @return false if the event is not supported
   */
  public ReplicatedChangeEventInfo getChangeEventInfo(Event newEvent) {
    ReplicatedChangeEventInfo changeEventInfo = new ReplicatedChangeEventInfo();

    //When we call a setter on the ChangeEventInfo instance, it sets the member value of
    //supported to true. There are four different categories of supported events below, namely
    //PatchSetEvents, ChangeEvents, RefEvents and ProjectEvents.

    //PatchSetEvents and ChangeEvents. Note a PatchSetEvent is a subclass of a ChangeEvent
    // TODO: (trevorg) Ask why do we not need to wrap the patchset of patchsetevent with its info?
    if (newEvent instanceof PatchSetEvent || newEvent instanceof ChangeEvent) {
      changeEventInfo.setChangeAttribute(((ChangeEvent) newEvent).change.get());
      //RefEvents
    } else if (newEvent instanceof RefEvent) {
      RefEvent refEvent = (RefEvent) newEvent;
      changeEventInfo.setProjectName(refEvent.getProjectNameKey().get());
      changeEventInfo.setBranchName(new Branch.NameKey(refEvent.getProjectNameKey().get(), completeRef(refEvent.getRefName())));
      //RefUpdatedEvent is a RefEvent but has a specific check on if the refUpdate field is null, therefore cannot
      // be grouped with the rest of the RefEvents
    } else if (newEvent instanceof com.google.gerrit.server.events.RefUpdatedEvent) {
      RefUpdatedEvent event = (RefUpdatedEvent) newEvent;
      if (event.refUpdate != null) {
        changeEventInfo.setProjectName(event.refUpdate.get().project);
        changeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(event.refUpdate.get().project), completeRef(event.refUpdate.get().refName)));
      } else {
        logger.atInfo().log("RE %s is not supported, project name or refupdate is null!", newEvent.getClass().getName());
        changeEventInfo.setSupported(false);
      }
      //ProjectEvents
    } else if (newEvent instanceof ProjectEvent) {
      ProjectEvent projectEvent = (ProjectEvent) newEvent;
      changeEventInfo.setProjectName(projectEvent.getProjectNameKey().get());
    } else {
      logger.atInfo().log("RE %s is not supported!", newEvent.getClass().getName());
      changeEventInfo.setSupported(false);
    }
    return changeEventInfo;
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
    final ReplicatedEventsWorker other = (ReplicatedEventsWorker) obj;
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
