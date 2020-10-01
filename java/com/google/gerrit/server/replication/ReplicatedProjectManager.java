
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

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DO_NOT_DELETE_PROJECT_FROM_DISK;
import static com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent.DeleteMessage.DELETE_PROJECT_FROM_DISK;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.server.git.GitRepositoryManager;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;

import java.io.IOException;
import java.util.List;

public class ReplicatedProjectManager implements Replicator.GerritPublishable {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedProjectManager.class);
  private static final Gson gson = new Gson();
  private static ReplicatedProjectManager instance = null;
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
    log.info("PROJECT About to call replicated project deletion event: {}, {}, {}", projectName, preserve, taskUuid);
    Replicator.getInstance().queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectEvent(projectInfoWrapper));
  }

  public static void replicateProjectChangeDeletion(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, String taskUuid) {
    DeleteProjectChangeEvent deleteProjectChangeEvent =
        new DeleteProjectChangeEvent(project, preserve, changesToBeDeleted, taskUuid, Replicator.getInstance().getThisNodeIdentity());
    log.info("PROJECT About to call replicated project change deletion event: {}, {}, {}, {}",
             project.getName(), preserve, changesToBeDeleted, taskUuid);
    Replicator.getInstance().queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectChangeEvent(deleteProjectChangeEvent));
  }

  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.getEventOrigin() == DELETE_PROJECT_EVENT) {
      // We can get into the if statement in 2 ways :
      // 1. we are doing a hard delete on the project, i.e. we want to completely remove the repo
      // 2. we are doing a soft delete, i.e. we only want to remove the projects changes, reviews etc
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        if (newEvent.getClassName().equals(DeleteProjectChangeEvent.class.getName())) {
          result = deleteProjectChanges(newEvent, eventClass);
        }else {
          result = deleteProject(newEvent, eventClass);
        }

      } catch(ClassNotFoundException e) {
        log.error("PROJECT event has been lost. Could not find {}",newEvent.getClassName(),e);
      }
    } else if (newEvent != null && newEvent.getEventOrigin() !=  DELETE_PROJECT_EVENT) {
        log.error("DELETE_PROJECT_EVENT event has been sent here but originartor is not the right one ({})",newEvent.getEventOrigin());
    }
    return result;
  }

  public static synchronized void enableReplicatedProjectManager() {
    if (instance == null) {
      instance = new ReplicatedProjectManager();
      log.info("PROJECT New instance created");
      Replicator.subscribeEvent(DELETE_PROJECT_EVENT, instance);
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

    log.info("RE Original event: {}",originalEvent.toString());
    originalEvent.replicated = true; // not needed, but makes it clear
    originalEvent.setNodeIdentity(Replicator.getInstance().getThisNodeIdentity());
    result = applyActionsForDeletingProject(originalEvent);

    DeleteProjectMessageEvent deleteProjectMessageEvent = null;
    if (result && !originalEvent.preserve) {
      // If the request was to remove the repository from the disk, then we do that only after all the nodes have replied
      // So first phase is to clear the data about the repo, 2nd phase is to remove it
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(originalEvent.projectName, DELETE_PROJECT_FROM_DISK,
          originalEvent.taskUuid, Replicator.getInstance().getThisNodeIdentity());
    } else {
      // If the result is false then we have failed the first part of the removal. If we are Preserving the repo then we do not
      // want to remove the repo so we send a failed response so we know not to remove it.
      deleteProjectMessageEvent = new DeleteProjectMessageEvent(originalEvent.projectName, DO_NOT_DELETE_PROJECT_FROM_DISK,
          originalEvent.taskUuid, Replicator.getInstance().getThisNodeIdentity());
    }
    Replicator.getInstance().queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectMessageEvent(deleteProjectMessageEvent));
    return result;
  }


  /**
   * Perform actions to delete all the changes associated with the project on all nodes.
   *
   * @param newEvent
   * @param eventClass
   */
  private boolean deleteProjectChanges(EventWrapper newEvent, Class<?> eventClass) {
    DeleteProjectChangeEvent originalEvent;
    boolean result = false;
    try {
      originalEvent = (DeleteProjectChangeEvent) gson.fromJson(newEvent.getEvent(), eventClass);
    } catch (JsonSyntaxException je) {
      log.error("DeleteProject, Could not decode json event {}", newEvent.toString(), je);
      return result;
    }

    if (originalEvent == null) {
      log.error("DeleteProject, fromJson method returned null for {}", newEvent.toString());
      return result;
    }

    log.info("RE Original event: {}",originalEvent.toString());
    originalEvent.replicated = true; // not needed, but makes it clear
    originalEvent.setNodeIdentity(Replicator.getInstance().getThisNodeIdentity());
    result = applyActionsForDeletingProjectChanges(originalEvent);
    return result;
  }


  /**
   * Remove the project from the jgit cache on all nodes
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
   * @param originalEvent
   * @return
   */
  private boolean applyActionsForDeletingProjectChanges(DeleteProjectChangeEvent originalEvent) {
    log.info("PROJECT event is about to remove the changes related to project {}. Original event was {}!", originalEvent.project.getName(), originalEvent);
    try {
      ReplicatedIndexEventManager.getInstance().deleteChanges(originalEvent.changes);
      return true;
    } catch (IOException e) {
      log.error("Error while deleting changes ", e);
    }
    return false;
 }

}
