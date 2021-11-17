package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.EventBroker;
import com.google.gerrit.common.replication.ReplicatedChangeEventInfo;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsMissingChangeInformationException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.*;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.gerrit.common.replication.feeds.ReplicatedOutgoingServerEventsFeed.getChangeEventInfo;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.GERRIT_EVENT;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedIncomingServerEventProcessor extends GerritPublishableImpl {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedIncomingServerEventProcessor.class);

  private EventBroker eventBroker;

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   *
   * @param replicatedEventsCoordinator
   */
  public ReplicatedIncomingServerEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(GERRIT_EVENT, replicatedEventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingServerEventProcessor.class);
  }

  /**
   * Using the sysInjector to get an instance of EventBroker to avoid using the coordinator
   * having to inject an EventBroker dependency in its constructor which would cause a dependency cycle.
   * The sysInjector is set up here: {@link com.google.gerrit.pgm.Daemon#createSysInjector()} () ConfigInjector}
   *
   * @return A singleton instance of the EventBroker.
   */
  public EventBroker getEventBroker() {
    if (eventBroker == null) {
      eventBroker = replicatedEventsCoordinator.getSysInjector().getInstance(EventBroker.class);
    }
    return eventBroker;
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  /**
   * This is the function implementing the GerritPublishable interface
   * aimed at receiving the event to be published
   *
   * @param newEvent
   * @return result
   */
  @Override
  public void publishIncomingReplicatedEvents(EventWrapper newEvent) {
    final ReplicatedConfiguration replicatedConfiguration =
        replicatedEventsCoordinator.getReplicatedConfiguration();

    if (replicatedConfiguration.isReceiveReplicatedEventsEnabled()) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        Event originalEvent = (Event) gson.fromJson(newEvent.getEvent(), eventClass);

        if (originalEvent == null) {
          throw new JsonSyntaxException("Event Json Parsing returning no valid event information from: " + newEvent.toString());
        }

        log.debug("RE Original event: {}", originalEvent.toString());
        originalEvent.replicated = true;
        originalEvent.setNodeIdentity(replicatedConfiguration.getThisNodeIdentity());

        if (replicatedConfiguration.isReplicatedEventsReplicateOriginalEvents()) {
          publishIncomingReplicatedEvents(originalEvent);
        }
      } catch (ReplicatedEventsUnknownTypeException e) {
        final String err = String.format("WARNING: Unable to publish a unknown/unsupported event using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
        log.warn(err);
        throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
      }
      // Just log info level with no stack trace so we don't spam the logs for
      // the following catch blocks
      catch (ClassNotFoundException e) {
        final String err = String.format("WARNING: Unable to publish a replicated event using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
        log.warn(err);
        throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
      }
    }
  }

  /**
   * Publishes the event calling the postEvent function in ChangeHookRunner
   *
   * @param newEvent
   * @return result
   */
  private void publishIncomingReplicatedEvents(Event newEvent) {
    ReplicatedChangeEventInfo replicatedChangeEventInfo = getChangeEventInfo(newEvent);

    if (replicatedChangeEventInfo == null) {
      return;
    }

    log.debug("RE going to fire event... {} ", replicatedChangeEventInfo);

    try (ReviewDb db = replicatedEventsCoordinator.getSchemaFactory().open()) {
      if (replicatedChangeEventInfo.getChangeAttr() != null) {
        log.debug("RE using changeAttr: {}...", replicatedChangeEventInfo.getChangeAttr());
        Change change = db.changes().get(Change.Id.parse(replicatedChangeEventInfo.getChangeAttr().number));

        // reworked as part of GER-1767
        // If change will be null its probably either a JSon changed Test case by QE, or somehow we
        // have a stream event coming in after a deletion - either way we can't compare timestamps so lets just
        // indicate missing change, and it will delete all working events before this one and backoff.
        if (change == null) {
          log.warn("Change {} was not present in the DB", replicatedChangeEventInfo.getChangeAttr().number);
          throw new ReplicatedEventsMissingChangeInformationException(
              String.format("Change %s was not present in the DB. It was either deleted or will be added " +
                  "by a future event", replicatedChangeEventInfo.getChangeAttr().number));
        }

        log.debug("RE got change from DB: {}", change);
        getEventBroker().postEvent(change, (ChangeEvent) newEvent);
      } else if (replicatedChangeEventInfo.getBranchName() != null) {
        log.debug("RE using branchName: {}", replicatedChangeEventInfo.getBranchName());
        getEventBroker().postEvent(replicatedChangeEventInfo.getBranchName(), (RefEvent) newEvent);
      } else if (newEvent instanceof ProjectCreatedEvent) {
        getEventBroker().postEvent(((ProjectCreatedEvent) newEvent));
      } else {
        log.error("RE Internal error, it's *supported*, but refs is null", new Exception("refs is null for supported event"));
        throw new ReplicatedEventsUnknownTypeException("RE Internal error, it's *supported*, but refs is null");
      }
    } catch (OrmException e) {
      log.error("RE While trying to publish a replicated event", e);

      // Something happened requesting this event information - lets treat at a missing case, and it will retry later.
      throw new ReplicatedEventsMissingChangeInformationException(
          String.format("Change %s was not returned from the DB due to ORMException (maybe it will be later).", replicatedChangeEventInfo.getChangeAttr().number));

    }
  }
}
