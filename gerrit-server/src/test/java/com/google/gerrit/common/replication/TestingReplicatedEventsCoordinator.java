package com.google.gerrit.common.replication;

import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingAccountIndexEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingIndexEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingProjectEventsFeed;
import com.google.gerrit.common.replication.modules.ReplicationModule;
import com.google.gerrit.common.replication.processors.GerritPublishable;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingAccountIndexEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingCacheEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingIndexEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingProjectEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingServerEventProcessor;
import com.google.gerrit.common.replication.workers.ReplicatedIncomingEventWorker;
import com.google.gerrit.common.replication.workers.ReplicatedOutgoingEventWorker;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.errors.ConfigInvalidException;

import java.util.Map;
import java.util.Properties;
import java.util.Set;


public class TestingReplicatedEventsCoordinator implements ReplicatedEventsCoordinator {

  ReplicatedConfiguration replicatedConfiguration;

  public TestingReplicatedEventsCoordinator() throws ConfigInvalidException {
    Properties testingProperties = new Properties();
    replicatedConfiguration = new ReplicatedConfiguration(testingProperties);
  }

  // allow supply of config.
  public TestingReplicatedEventsCoordinator( Properties testingProperties) throws Exception {
    replicatedConfiguration = new ReplicatedConfiguration(testingProperties);
    ensureEventsDirectoriesExistForTests();
  }

  @Override
  public boolean isGerritIndexerRunning() {
    return false;
  }

  @Override
  public ChangeIndexer getChangeIndexer() {
    return null;
  }

  @Override
  public AccountIndexer getAccountIndexer() {
    return null;
  }

  @Override
  public String getThisNodeIdentity() {
    return "SomeTestNodeID";
  }

  @Override
  public ReplicatedConfiguration getReplicatedConfiguration() {
    return replicatedConfiguration;
  }

  @Override
  public Gson getGson() {
    return new ReplicationModule().provideGson();
  }

  @Override
  public GitRepositoryManager getGitRepositoryManager() {
    return null;
  }

  @Override
  public Map<EventWrapper.Originator, Set<GerritPublishable>> getReplicatedProcessors() {
    return null;
  }

  @Override
  public SchemaFactory<ReviewDb> getSchemaFactory() {
    return null;
  }

  @Override
  public void subscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {

  }

  @Override
  public void unsubscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {

  }

  @Override
  public boolean isCacheToBeEvicted(String cacheName) {
    return false;
  }

  @Override
  public void queueEventForReplication(EventWrapper event) {

  }

  @Override
  public ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingAccountIndexEventProcessor getReplicatedIncomingAccountIndexEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor() {
    return null;
  }

  @Override
  public ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedOutgoingAccountIndexEventsFeed getReplicatedOutgoingAccountIndexEventsFeed() {
    return null;
  }

  @Override
  public ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker() {
    return null;
  }

  @Override
  public ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker() {
    return null;
  }

  @Override
  public ReplicatedScheduling getReplicatedScheduling() {
    return null;
  }

  @Override
  public void start() {

  }

  @Override
  public void stop() {
  }

  @Override
  public Injector getSysInjector() {
    return null;
  }

  @Override
  public void setSysInjector(Injector sysInjector) {
  }

  private void ensureEventsDirectoriesExistForTests() throws Exception {
    if (!replicatedConfiguration.getIncomingReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getIncomingReplEventsDirectory().mkdirs()) {
        throw new Exception("RE {} path cannot be created! Replicated events will not work!" +
            replicatedConfiguration.getIncomingReplEventsDirectory().getAbsolutePath());
      }

    }
    if (!replicatedConfiguration.getOutgoingReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getOutgoingReplEventsDirectory().mkdirs()) {
        throw new Exception("RE {} path cannot be created! Replicated outgoing events will not work!" +
            replicatedConfiguration.getOutgoingReplEventsDirectory().getAbsolutePath());
      }

    }
    if (!replicatedConfiguration.getIncomingFailedReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getIncomingFailedReplEventsDirectory().mkdirs()) {
        throw new Exception("RE {} path cannot be created! Replicated failed events will not work!" +
            replicatedConfiguration.getIncomingFailedReplEventsDirectory().getAbsolutePath());
      }
    }
  }

}
