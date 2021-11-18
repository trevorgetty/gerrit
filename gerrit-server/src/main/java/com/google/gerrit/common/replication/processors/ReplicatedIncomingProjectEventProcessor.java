package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.DeleteProjectChangeEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.ProjectInfoWrapper;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DELETE_PROJECT_FROM_DISK;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DO_NOT_DELETE_PROJECT_FROM_DISK;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;

@Singleton
public class ReplicatedIncomingProjectEventProcessor extends GerritPublishableImpl {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedIncomingProjectEventProcessor.class);
  private GitRepositoryManager repoManager;

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
  public ReplicatedIncomingProjectEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(DELETE_PROJECT_EVENT, replicatedEventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
    this.repoManager = replicatedEventsCoordinator.getGitRepositoryManager();
    SingletonEnforcement.registerClass(ReplicatedIncomingProjectEventProcessor.class);
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  @Override
  public void publishIncomingReplicatedEvents(EventWrapper newEvent) {

    if (newEvent.getEventOrigin() != EventWrapper.Originator.DELETE_PROJECT_EVENT) {
      log.error("Event has been sent here but originator / origin pair is not the right one for this event type.({})",
          newEvent);

      throw new ReplicatedEventsUnknownTypeException(
          String.format("Event has been sent to DELETE_PROJECT_EVENT but originator is not the right one (%s)", newEvent));
    }

    // We can get into the if statement in 2 ways :
    // 1. we are doing a hard delete on the project, i.e. we want to completely remove the repo
    // 2. we are doing a soft delete, i.e. we only want to remove the projects changes, reviews etc
    try {
      Class<?> eventClass = Class.forName(newEvent.getClassName());
      if (newEvent.getClassName().equals(DeleteProjectChangeEvent.class.getName())) {
        deleteProjectChanges(newEvent, eventClass);
      } else {
        deleteProject(newEvent, eventClass);
      }
    } catch (ClassNotFoundException e) {
      final String err = String.format("WARNING: Unable to publish a replicated event using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
      log.warn(err);
      throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
    }

  }

  /**
   * Perform actions to actually delete the project on all nodes and send round a message
   * that the node has successfully deleted the project
   *
   * @param newEvent
   * @param eventClass
   */
  private boolean deleteProject(EventWrapper newEvent, Class<?> eventClass) {
    ProjectInfoWrapper originalEvent = null;
    boolean result = false;

    try {
      originalEvent = (ProjectInfoWrapper) gson.fromJson(newEvent.getEvent(), eventClass);
    } catch (JsonSyntaxException je) {
      log.error("DeleteProject, Could not decode json event {}", newEvent.toString(), je);
      return result;
    }

    if (originalEvent == null) {
      log.error("DeleteProject, fromJson method returned null for {}", newEvent.toString());
      return result;
    }

    log.info("RE Original event: {}", originalEvent.toString());
    originalEvent.replicated = true; // not needed, but makes it clear
    originalEvent.setNodeIdentity(replicatedEventsCoordinator.getThisNodeIdentity());
    result = applyActionsForDeletingProject(originalEvent);

    DeleteProjectMessageEvent deleteProjectMessageEvent = null;
    if (result && !originalEvent.preserve) {
      // If the request was to remove the repository from the disk, then we do that only after all the nodes have replied
      // So first phase is to clear the data about the repo, 2nd phase is to remove it
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(originalEvent.projectName, DELETE_PROJECT_FROM_DISK,
          originalEvent.taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    } else {
      // If the result is false then we have failed the first part of the removal. If we are Preserving the repo then we do not
      // want to remove the repo so we send a failed response so we know not to remove it.
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(originalEvent.projectName, DO_NOT_DELETE_PROJECT_FROM_DISK,
          originalEvent.taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    }
    try {
      replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectMessageEvent(deleteProjectMessageEvent));
      return result;
    } catch (IOException e) {
      // unable to create the event wrapper - so can't replicate this change record the error.
      // We do want to let this deletion complete locally
      log.error("Unable to create event wrapper and queue this DeleteProjectMessageEvent.", e);
      return result;
    }
  }


  /**
   * Perform actions to delete all the changes associated with the project on all nodes.
   *
   * @param newEvent
   * @param eventClass
   */
  private boolean deleteProjectChanges(EventWrapper newEvent, Class<?> eventClass) {
    DeleteProjectChangeEvent originalEvent;
    try {
      originalEvent = (DeleteProjectChangeEvent) gson.fromJson(newEvent.getEvent(), eventClass);
    } catch (JsonSyntaxException je) {
      log.error("PR Could not decode json event {}", newEvent, je);
      throw new JsonSyntaxException(String.format("PR Could not decode json event %s", newEvent), je);
    }

    if (originalEvent == null) {
      throw new JsonSyntaxException("Event Json Parsing returning no valid event information from: " + newEvent);
    }

    log.info("RE Original event: {}", originalEvent.toString());
    originalEvent.replicated = true; // not needed, but makes it clear
    originalEvent.setNodeIdentity(replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity());
    return applyActionsForDeletingProjectChanges(originalEvent);
  }


  /**
   * Remove the project from the jgit cache on all nodes
   *
   * @param originalEvent
   * @return
   */
  private boolean applyActionsForDeletingProject(ProjectInfoWrapper originalEvent) {
    log.info("PROJECT event is about to remove the project from the jgit cache. Original event was {}!", originalEvent);
    Project.NameKey nameKey = new Project.NameKey(originalEvent.projectName);
    Repository repository;
    try {
      repository = repoManager.openRepository(nameKey);
      // The cleanCache() method in FileSystemDeleteHandler performs the following 2 calls
      repository.close();
      RepositoryCache.close(repository);
      return true;
    } catch (RepositoryNotFoundException e) {
      log.error("Could not locate Repository {}", nameKey, e);
    } catch (IOException e) {
      log.error("Could not open Repository {}", nameKey, e);
    }

    return false;
  }

  /**
   * Remove the changes associated with the project on all nodes
   *
   * @param originalEvent
   * @return
   */
  private boolean applyActionsForDeletingProjectChanges(DeleteProjectChangeEvent originalEvent) {
    log.info("PROJECT event is about to remove the changes related to project {}. Original event was {}!", originalEvent.project.getName(), originalEvent);
    try {
      replicatedEventsCoordinator.getReplicatedIncomingIndexEventProcessor().deleteChanges(originalEvent.changes);
      return true;
    } catch (IOException e) {
      log.error("Error while deleting changes ", e);
    }
    return false;
  }

}
