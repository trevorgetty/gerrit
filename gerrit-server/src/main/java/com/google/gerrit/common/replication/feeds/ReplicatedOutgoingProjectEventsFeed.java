package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.DeleteProjectChangeEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.ProjectInfoWrapper;
import com.google.gerrit.common.replication.ConfigureReplication;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedOutgoingProjectEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedOutgoingProjectEventsFeed.class);

  private static ReplicatedOutgoingProjectEventsFeed INSTANCE;

  private ReplicatedOutgoingProjectEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
  }

  //Get singleton instance
  public static ReplicatedOutgoingProjectEventsFeed getInstance(ReplicatedEventsCoordinator eventsCoordinator) {
    if(INSTANCE == null) {
      INSTANCE = new ReplicatedOutgoingProjectEventsFeed(eventsCoordinator);
      SingletonEnforcement.registerClass(ReplicatedOutgoingProjectEventsFeed.class);
    }

    return INSTANCE;
  }


  public void replicateProjectDeletion(String projectName, boolean preserve, String taskUuid) throws IOException {
    ProjectInfoWrapper projectInfoWrapper = new ProjectInfoWrapper(projectName, preserve, taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    log.info("PROJECT About to call replicated project deletion event: {},{},{}",
        projectName, preserve, taskUuid);
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectEvent(projectInfoWrapper));
  }

  public void replicateProjectChangeDeletion(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, String taskUuid) throws IOException {
    DeleteProjectChangeEvent deleteProjectChangeEvent =
        new DeleteProjectChangeEvent(project, preserve, changesToBeDeleted, taskUuid, replicatedEventsCoordinator.getThisNodeIdentity());
    log.info("PROJECT About to call replicated project change deletion event: {},{},{},{}",
        project.getName(), preserve, changesToBeDeleted, taskUuid);
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedDeleteProjectChangeEvent(deleteProjectChangeEvent));
  }

}
