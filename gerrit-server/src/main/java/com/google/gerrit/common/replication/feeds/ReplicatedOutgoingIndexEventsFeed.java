package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.ConfigureReplication;
import com.google.gerrit.common.replication.IndexToReplicate;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedOutgoingIndexEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedOutgoingIndexEventsFeed.class);

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   * @param eventsCoordinator
   */
  public ReplicatedOutgoingIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingIndexEventsFeed.class);
  }

  /**
   * Main method used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes.
   * <p>
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  public void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean safeToIgnoreMissingChange) throws IOException {
    queueReplicationIndexEvent(indexNumber, projectName, lastUpdatedOn, false, safeToIgnoreMissingChange);
  }

  /**
   * Queue a notification to be made to replica nodes regarding the deletion of an index. This must be done
   * independently of the delete() call in ChangeIndexer, as in that context, the Change is no longer
   * accessible, preventing lookup of the project name and the subsequent attempt to tie the change to
   * a specific DSM in the replicator.
   *
   * @param indexNumber
   * @param projectName
   */
  public void queueReplicationIndexDeletionEvent(int indexNumber, String projectName) throws IOException {
    queueReplicationIndexEvent(indexNumber, projectName, new Timestamp(System.currentTimeMillis()), true, false);
  }

  /**
   * Used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes with an additional boolean flag to indicate if the index
   * to be updated is being deleted.
   * <p>
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  private void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean deleteIndex, boolean safeToIgnoreMissingChange) throws IOException {
    if (replicatedEventsCoordinator.isGerritIndexerRunning()) { // we only take the event if it's normal Gerrit functioning. If it's indexing we ignore them
      IndexToReplicate indexToReplicate =
          new IndexToReplicate(indexNumber, projectName, lastUpdatedOn, deleteIndex,
              replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity(), safeToIgnoreMissingChange);
      replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedIndexEvent(indexToReplicate));
      log.debug("RC Just added {} to cache queue", indexToReplicate);
    }
  }
}
