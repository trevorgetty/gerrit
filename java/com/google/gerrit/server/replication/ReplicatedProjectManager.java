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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;

import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DO_NOT_DELETE_PROJECT_FROM_DISK;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DELETE_PROJECT_FROM_DISK;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import com.google.gerrit.server.git.GitRepositoryManager;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ReplicatedProjectManager implements ReplicatedEventProcessor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static volatile ReplicatedProjectManager instance = null;
  private static GitRepositoryManager repoManager;

  private ReplicatedProjectManager() {
  }

  /**
   * Make use of the GitRepositoryManager, set on startup in ChangeUtil
   */
  public static void setRepoManager(GitRepositoryManager gitRepositoryManager){
    if(repoManager == null){
      repoManager = gitRepositoryManager;
    }
  }

  public static void replicateProjectDeletion(String projectName, boolean preserve, String taskUuid) {
    ProjectInfoWrapper projectInfoWrapper = new ProjectInfoWrapper(projectName, preserve, taskUuid, Replicator.getInstance().getThisNodeIdentity());
    logger.atInfo().log("PROJECT About to call replicated project deletion event: %s, %s, %s", projectName, preserve, taskUuid);
    Replicator.getInstance().queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectEvent(projectInfoWrapper));
  }

  public static void replicateProjectChangeDeletion(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, String taskUuid) {
    DeleteProjectChangeEvent deleteProjectChangeEvent =
        new DeleteProjectChangeEvent(project, preserve, changesToBeDeleted, taskUuid, Replicator.getInstance().getThisNodeIdentity());
    logger.atInfo().log("PROJECT About to call replicated project change deletion event: %s, %s, %s, %s",
             project.getName(), preserve, changesToBeDeleted, taskUuid);
    Replicator.getInstance().queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectChangeEvent(deleteProjectChangeEvent));
  }

  /**
   * We call this method if we are dealing with a single node replication
   * group. For a single node membership we need to create a DeleteProjectMessageEvent
   * instead of using a wrapped ProjectInfoWrapper.
   * @param project : Gerrit project to be deleted.
   * @param preserve : When preserve is true, the project is not removed from disk
   * @param taskUuid : unique UUID which allows for tracking the delete request.
   */
  public static void deleteProjectSingleNodeGroup(Project project, boolean preserve,
                                                  final String taskUuid) {

    //Delete the project from the jgit cache on a single node
    boolean jgitCacheChangesRemoved = applyActionsForDeletingProject(
        new ProjectInfoWrapper(project.getName(), preserve, taskUuid,
            Objects.requireNonNull(Replicator.getInstance()).getThisNodeIdentity()));

    // Send a DELETE_PROJECT_MESSAGE_EVENT which will be picked up by the
    // GerritEventStreamProposer in the outgoing and which will be used to
    // send the GerritDeleteProjectProposal to remove the node from the GerritDeleteRepositoryTask.

    createDeleteProjectMessageEvent(taskUuid,
        jgitCacheChangesRemoved && !preserve, project.getName());
  }


  /**
   * Creates new instance of DeleteProjectMessageEvent
   * if the value of deleteFromDisk is true then we construct the
   * instance with the enum DELETE_PROJECT_FROM_DISK otherwise we construct
   * it with DO_NOT_DELETE_PROJECT_FROM_DISK
   * @param taskUuid : The taskId
   * @param deleteFromDisk : boolean whether to delete project from disk or not
   * @param name : name of the repository to either delete from disk or to delete changes for.
   */
  private static void createDeleteProjectMessageEvent(String taskUuid, boolean deleteFromDisk, String name) {
    Replicator replicator = Replicator.getInstance();
    DeleteProjectMessageEvent deleteProjectMessageEvent;
    if (deleteFromDisk) {
      // If the request was to remove the repository from the disk, then we do that only after all the nodes have replied
      // So first phase is to clear the data about the repo, 2nd phase is to remove it
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(name, DELETE_PROJECT_FROM_DISK,
          taskUuid, Objects.requireNonNull(replicator).getThisNodeIdentity());
    } else {
      // If the result is false then we have failed the first part of the removal. If we are Preserving the repo then we do not
      // want to remove the repo so we send a failed response so we know not to remove it.
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(name, DO_NOT_DELETE_PROJECT_FROM_DISK,
          taskUuid, Objects.requireNonNull(replicator).getThisNodeIdentity());
    }

    //Queue the event for the replicator
    Objects.requireNonNull(Replicator.getInstance()).queueEventForReplication(GerritEventFactory
        .createReplicatedDeleteProjectMessageEvent(deleteProjectMessageEvent));
  }

  /**
   * Processes an incoming replicated event that can be cast to one of two types. A DeleteProjectChangeEvent
   * details changes that need to be deleted from all nodes. ProjectInfoWrapper is used in a context of deciding
   * to delete a project from disk or preserve the project on disk.
   * @param replicatedEvent Will be cast to either a DeleteProjectChangeEvent or ProjectInfoWrapper
   * @return true if the deletion of project changes or indeed the entire project succeeds.
   * @throws IOException if there are issues with deleting open changes or issues with deleting the repository
   * on disk.
   */
  @Override
  public boolean processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) throws IOException {
    if(replicatedEvent == null) return false;
    // We can get into the if statement in 2 ways :
    // 1. we are doing a hard delete on the project, i.e. we want to completely remove the repo
    // 2. we are doing a soft delete, i.e. we only want to remove the projects changes, reviews etc
    if (replicatedEvent instanceof DeleteProjectChangeEvent) {
      DeleteProjectChangeEvent deleteProjectChangeEvent = (DeleteProjectChangeEvent) replicatedEvent;
      return deleteProjectChanges(deleteProjectChangeEvent);
    }

    ProjectInfoWrapper projectInfoWrapper = (ProjectInfoWrapper) replicatedEvent;
    return deleteProject(projectInfoWrapper);
  }


  public static void enableReplicatedProjectManager() {
    if (instance == null) {
      synchronized (ReplicatedProjectManager.class) {
        if (instance == null) {
          instance = new ReplicatedProjectManager();
          logger.atInfo().log("PROJECT New instance created");
          Replicator.subscribeEvent(DELETE_PROJECT_EVENT, instance);
        }
      }
    }
  }

  /**
   * Perform actions to actually delete the project on all nodes and send round a message
   * that the node has successfully deleted the project
   * @param projectInfoWrapper Wraps data required for a DeleteProjectMessageEvent
   * @return true if we succeed in deleting the project from the jgit cache and we are able to send
   * a DeleteProjectMessageEvent with the data from the ProjectInfoWrapper.
   */
  private boolean deleteProject(ProjectInfoWrapper projectInfoWrapper) {
    boolean result;

    if (projectInfoWrapper == null) {
      logger.atSevere().log("Received null ProjectInfoWrapper");
      return false;
    }

    logger.atInfo().log("RE Original event: %s",projectInfoWrapper.toString());
    projectInfoWrapper.replicated = true; // not needed, but makes it clear
    projectInfoWrapper.setNodeIdentity(Objects.requireNonNull(Replicator.getInstance()).getThisNodeIdentity());
    result = applyActionsForDeletingProject(projectInfoWrapper);

    createDeleteProjectMessageEvent(projectInfoWrapper.taskUuid, result &&
        !projectInfoWrapper.preserve, projectInfoWrapper.projectName);
    return result;
  }


  /**
   * Perform actions to delete all the changes associated with the project on all nodes.
   * @param deleteProjectChangeEvent : Event type used for the purpose of deleting open changes for a given project
   * @return true if project changes have been successfully deleted.
   */
  private boolean deleteProjectChanges(DeleteProjectChangeEvent deleteProjectChangeEvent) {

    if (deleteProjectChangeEvent == null) {
      logger.atSevere().log("Received null DeleteProjectChangeEvent");
      return false;
    }

    logger.atInfo().log("Original event: %s", deleteProjectChangeEvent.toString());
    deleteProjectChangeEvent.replicated = true; // not needed, but makes it clear
    deleteProjectChangeEvent.setNodeIdentity(Objects.requireNonNull(Replicator.getInstance()).getThisNodeIdentity());
    return applyActionsForDeletingProjectChanges(deleteProjectChangeEvent);
  }


  /**
   * Remove the project from the jgit cache on all nodes
   * @param projectInfoWrapper : Event type used for the purpose of wrapping a DeleteProjectMessageEvent
   *                           which carries the data about the project to be removed or preserved.
   * @return true if the repository is successfully removed from the jgit cache.
   */
  private static boolean applyActionsForDeletingProject(ProjectInfoWrapper projectInfoWrapper) {

    logger.atInfo().log("ProjectInfoWrapper event is about to remove the project from the jgit cache. " +
        "Original event was %s!", projectInfoWrapper);

    Project.NameKey nameKey = new Project.NameKey(projectInfoWrapper.projectName);
    Repository repository;
    try {
      repository = repoManager.openRepository(nameKey);
      // The cleanCache() method in FileSystemDeleteHandler performs the following 2 calls
      repository.close();
      RepositoryCache.close(repository);
      return true;
    } catch (RepositoryNotFoundException e) {
      logger.atSevere().log("Could not locate Repository %s", nameKey, e);
    } catch (IOException e) {
      logger.atSevere().log("Could not open Repository %s", nameKey, e);
    }

    return false;
 }

  /**
   * Remove the changes associated with the project on all nodes
   * @param deleteProjectChangeEvent : Event type used for the purpose of deleting open changes for a given project
   * @return true if successfully performed a non replicated delete of the open changes.
   */
  private boolean applyActionsForDeletingProjectChanges(DeleteProjectChangeEvent deleteProjectChangeEvent) {
    logger.atInfo().log("DeleteProjectChangeEvent event is about to remove the changes related to project %s. " +
        "Original event was %s!", deleteProjectChangeEvent.project.getName(), deleteProjectChangeEvent);
    try {
      ReplicatedIndexEventManager.getInstance().deleteChanges(deleteProjectChangeEvent.changes);
      return true;
    } catch (IOException e) {
      logger.atSevere().log("Error while deleting changes ", e);
    }
    return false;
 }

}
