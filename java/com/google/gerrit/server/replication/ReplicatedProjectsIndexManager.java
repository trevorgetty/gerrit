package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;
import com.wandisco.gerrit.gitms.shared.ReplicationConstants;

import java.io.IOException;

public class ReplicatedProjectsIndexManager implements Replicator.GerritPublishable {

	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
	private static final Gson gson = new Gson();

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
	 *
	 * @param nameKey
	 */
	public void replicateReindex(Project.NameKey nameKey) {

		ProjectIndexEvent indexEvent = new ProjectIndexEvent(nameKey, replicatorInstance.getThisNodeIdentity());
		replicatorInstance.queueEventForReplication(
				GerritEventFactory.createReplicatedProjectsIndexEvent(ReplicationConstants.ALL_PROJECTS, indexEvent));
	}


	@Override
	public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
		boolean result = false;

		if (newEvent == null) {
			logger.atFine().log("RC : Received null event");
			return false;
		}

		if (newEvent.getEventOrigin() == originator) {
			try {
				Class<?> eventClass = Class.forName(newEvent.getClassName());
				//Making the call for the local site.
				//i.e we don't need to make a replicated reindex now as we are receiving the
				//incoming replicated event to reindex.
				result = reindexProject(newEvent, eventClass);
			} catch (ClassNotFoundException e) {
				logger.atSevere().withCause(e).log("RC Projects event reindex has been lost. Could not find %s",
												   newEvent.getClassName());
			}
		}
		return result;
	}

	/**
	 * Local reindex of the Projects event.
	 *
	 * @param newEvent
	 * @param eventClass
	 * @return
	 */
	private boolean reindexProject(EventWrapper newEvent, Class<?> eventClass) {
		try {
			ProjectIndexEvent indexEvent = (ProjectIndexEvent) gson.fromJson(newEvent.getEvent(), eventClass);
			indexer.indexNoRepl(indexEvent.getIdentifier());
			return true;
		} catch (JsonSyntaxException | IOException e) {
			logger.atSevere().withCause(e).log("RC Project reindex, Could not decode json event %s",
				newEvent.toString());
			return false;
		}
	}
}
