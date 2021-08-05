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
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.reviewdb.client.Project;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;
import com.wandisco.gerrit.gitms.shared.ReplicationConstants;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.IOException;

public class ReplicatedProjectsIndexManager implements ReplicatedEventProcessor {

	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
	private ProjectIndexer indexer = null;
	private Originator originator = null;

	/**
	 * setIndexer
	 *
	 * @param indexer
	 * @param event
	 */
	public void setIndexer(ProjectIndexer indexer, Originator event) {
		this.indexer = indexer;
		originator = event;
		//Only subscribe for the instance if not already subscribed
		if (!replicatorInstance.isSubscribed(originator)) {
			Replicator.subscribeEvent(originator, this);
		}
	}

	// Using instance variables, to avoid contention / thread issues now this is a non-singleton class.
	private Replicator replicatorInstance = null;


  public interface Factory {

		/**
		 * Returns a ReplicatedProjectsIndexManager instance.
		 *
		 * @return
		 */
		static ReplicatedProjectsIndexManager create() {
			if (Replicator.isReplicationDisabled()) {
				logger.atInfo().log("RC Not creating ReplicatedProjectsIndexManager as GitMS is disabled in Gerrit.");
				return null;
			}
			return new ReplicatedProjectsIndexManager();
		}

		/**
		 * Set a project indexer for ReplicatedProjectsIndexManager instance
		 *
		 * @param indexer
		 * @return
		 */
		static ReplicatedProjectsIndexManager create(ProjectIndexer indexer) {
			ReplicatedProjectsIndexManager replicatedProjectsIndexManager = create();
			if (replicatedProjectsIndexManager == null) {
				return null;
			}
			//We get the ReplicatedProjectsIndexManager instance using the create() method, then
			//we can set the indexer for the instance.
			replicatedProjectsIndexManager.setIndexer(indexer, Originator.PROJECTS_INDEX_EVENT);
			return replicatedProjectsIndexManager;
		}

	}

	public ReplicatedProjectsIndexManager() {
		replicatorInstance = Replicator.getInstance(true);
		logger.atInfo().log("Created ReplicatedProjectsIndexManager");
	}

  /**
   * Queues a ProjectsIndexEvent
   * The ProjectsIndexEvent can be constructed with a boolean flag to state
   * whether or not the index event is to delete the project from the index.
   * @param nameKey: The name of the project to replicate the reindex for.
   */
  public void replicateReindex(Project.NameKey nameKey, boolean deleteFromIndex) {

    ProjectIndexEvent indexEvent = new ProjectIndexEvent(nameKey,
        replicatorInstance.getThisNodeIdentity(), deleteFromIndex);

    replicatorInstance.queueEventForReplication(
        GerritEventFactory.createReplicatedProjectsIndexEvent(ReplicationConstants.ALL_PROJECTS, indexEvent));
  }


  /**
   * Processes incoming ReplicatedEvent which is cast to a ProjectIndexEvent
   * This method then calls reindexProject which will produce a non replicated local reindex
   * of the Projects Index.
   * Processes originator type PROJECTS_INDEX_EVENT
   * @param replicatedEvent Base event type for all replicated events
   * @return true if the local reindex of the Projects Index has been completed successfully.
   * @throws IOException if there is an issue when deleting or adding to the local index.
   */
  @Override
  public boolean processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) throws IOException {
      return replicatedEvent != null && reindexProject((ProjectIndexEvent) replicatedEvent);
  }

	/**
	 * Local reindex of the Projects event.
	 * This can be either a deletion from an index or an update to
   * an index.
	 * @return : Returns true if the indexing operation has completed.
	 */
  private boolean reindexProject(ProjectIndexEvent projectIndexEvent) throws IOException {
      // If the index is to be deleted, indicated by the boolean flag in the ProjectIndexEvent
      // then we will delete the index. Otherwise it is a normal reindex.
      if (projectIndexEvent.isDeleteIndex()) {
        indexer.deleteIndexNoRepl(projectIndexEvent.getIdentifier());
      } else {
        indexer.indexNoRepl(projectIndexEvent.getIdentifier());
      }
      return true;
  }
}
