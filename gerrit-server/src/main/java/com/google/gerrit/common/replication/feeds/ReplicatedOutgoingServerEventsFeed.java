package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.EventBroker;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.ReplicatedChangeEventInfo;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.*;
import com.google.gerrit.server.events.Event;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Singleton //This class is guice bound
public class ReplicatedOutgoingServerEventsFeed implements LifecycleListener {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedOutgoingServerEventsFeed.class);

  private ReplicatedEventsCoordinator coordinator;
  private ReplicatedConfiguration configuration;
  private EventBroker eventBroker;

  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      bind(ReplicatedOutgoingServerEventsFeed.class);
      /* We need to bind the listener to this class as its required to started the lifecycle*/
      listener().to(ReplicatedOutgoingServerEventsFeed.class);
    }
  }

  @Inject
  public ReplicatedOutgoingServerEventsFeed(ReplicatedConfiguration configuration,
                                            ReplicatedEventsCoordinator coordinator,
                                            EventBroker eventBroker) {
    this.eventBroker = eventBroker;
    this.coordinator = coordinator;
    this.configuration = configuration;
    SingletonEnforcement.registerClass(ReplicatedOutgoingServerEventsFeed.class);
  }

  /**
   * Invoked when the server is starting.
   */
  @Override
  public void start() {
    this.eventBroker.addUnrestrictedEventListener(this.listener);
  }

  /**
   * Invoked when the server is stopping.
   */
  @Override
  public void stop() {
    SingletonEnforcement.unregisterClass(ReplicatedOutgoingServerEventsFeed.class);
  }


  public final EventListener listener = new EventListener() {

    @Override
    public void onEvent(Event event) {
      //If the event is in a skip list then we do not queue it for replication.
      if (!isEventToBeSkipped(event)) {
        event.setNodeIdentity(configuration.getThisNodeIdentity());
        try {
          ReplicatedChangeEventInfo changeEventInfo = getChangeEventInfo(event);
          if ( changeEventInfo == null ){
            // we dont support this event, skip over it - we can't queue it for replication.
            return;
          }
          coordinator.queueEventForReplication(GerritEventFactory.createReplicatedChangeEvent(event, changeEventInfo));
        } catch (IOException e) {
          log.error("Unable to queue server event for replication {}", e.getMessage());
        }
      } else {
        log.debug("Event type {} is present in the event skip list. Skipping.", event.getType());
      }
    }
  };

  /**
   * isEventToBeSkipped uses 2 things.
   * 1) has the event previously been replicated - if so we don't do it again!!
   * 2) Is the event in a list of events we are not to replicate ( i.e a skip list )
   * NOTE: By default we are skipping two events associated with the Replication plugin
   * as these events should not be replicated.
   *
   * @param event The event instance to check is skipped
   * @return true if event type is to be skipped.
   */
  public boolean isEventToBeSkipped(Event event) {
    if (event.replicated) {
      // don't cause cyclic loop replicating forever./
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
    return configuration.getEventSkipList().contains(event.getClass().getSimpleName().toLowerCase()); //short name of the class
  }

  /**
   * Since the event can be of many different types, and since the Gerrit engineers didn't want
   * to put the ChangeAttribute in the main abstract class, we have to analyze every
   * single event type and extract the relevant information
   *
   * @param newEvent
   * @return false if the event is not supported
   */
  public static ReplicatedChangeEventInfo getChangeEventInfo(Event newEvent) {
    ReplicatedChangeEventInfo replicatedChangeEventInfo = new ReplicatedChangeEventInfo();
    if (newEvent instanceof com.google.gerrit.server.events.ChangeAbandonedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((ChangeAbandonedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeMergedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((ChangeMergedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ChangeRestoredEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((ChangeRestoredEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.CommentAddedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((CommentAddedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.CommitReceivedEvent) {
      CommitReceivedEvent event = (CommitReceivedEvent) newEvent;
      replicatedChangeEventInfo.setProjectName(event.project.getName());
      replicatedChangeEventInfo.setBranchName(new Branch.NameKey(event.project.getNameKey(), completeRef(event.refName)));
    } else if (newEvent instanceof com.google.gerrit.server.events.DraftPublishedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((DraftPublishedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.PatchSetCreatedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((PatchSetCreatedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.RefUpdatedEvent) {
      RefUpdatedEvent event = (RefUpdatedEvent) newEvent;
      if (event.refUpdate != null) {
        replicatedChangeEventInfo.setProjectName(event.refUpdate.get().project);
        replicatedChangeEventInfo.setBranchName(new Branch.NameKey(new Project.NameKey(event.refUpdate.get().project),
            completeRef(event.refUpdate.get().refName)));
      } else {
        log.warn("RE {} is not supported, project name or refupdate is null, not replicating.", newEvent.getClass().getName());
        return null;
      }
    } else if (newEvent instanceof com.google.gerrit.server.events.ReviewerAddedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((ReviewerAddedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ReviewerDeletedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((ReviewerDeletedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.TopicChangedEvent) {
      replicatedChangeEventInfo.setChangeAttribute(((TopicChangedEvent) newEvent).change.get());
    } else if (newEvent instanceof com.google.gerrit.server.events.ProjectCreatedEvent) {
      replicatedChangeEventInfo.setProjectName(((ProjectCreatedEvent) newEvent).projectName);
    } else {
      log.warn("RE {} is not supported!, no processing in our code for this type - add support, or add to skipped list to remove this warning.",
          newEvent.getClass().getName());
      return null;
    }
    return replicatedChangeEventInfo;
  }

  /**
   * Helper method for the authentication in Gerrit
   */
  public static String completeRef(String refName) {
    if (refName == null) {
      return "";
    }
    // A refName can contain a "/" for example 'refs/heads/foo/bar' is a valid ref.
    // if refName starts with refs/heads/ already then just return refName otherwise prepend it with 'refs/heads'
    return refName.contains("refs/heads/") ? refName : "refs/heads/" + refName;
  }

}
