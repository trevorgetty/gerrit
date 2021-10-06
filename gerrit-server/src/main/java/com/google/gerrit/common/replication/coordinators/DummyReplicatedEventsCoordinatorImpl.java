package com.google.gerrit.common.replication.coordinators;

import com.google.gerrit.common.replication.ReplicatedScheduling;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingAccountIndexEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingIndexEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingProjectEventsFeed;
import com.google.gerrit.common.replication.processors.GerritPublishable;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
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
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.util.Map;
import java.util.Set;

@Singleton
public class DummyReplicatedEventsCoordinatorImpl implements ReplicatedEventsCoordinator {

  private final ReplicatedConfiguration replicatedConfiguration;

  @Inject
  DummyReplicatedEventsCoordinatorImpl(ReplicatedConfiguration replicatedConfiguration) {
    this.replicatedConfiguration = replicatedConfiguration;
  }

  @Override
  public Injector getSysInjector() {
    return null;
  }

  @Override
  public void setSysInjector(Injector sysInjector) { }

  @Override
  public void start() {

  }

  @Override
  public void stop() {

  }

  @Override
  public boolean isGerritIndexerRunning() {
    return false;
  }

  @Override
  public ChangeIndexer getChangeIndexer() {
    throw new UnsupportedOperationException("getChangeIndexer: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public AccountIndexer getAccountIndexer() {
    throw new UnsupportedOperationException("getAccountIndexer: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedConfiguration getReplicatedConfiguration() {
    return replicatedConfiguration;
  }

  @Override
  public Gson getGson() {
    throw new UnsupportedOperationException("getGson: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public GitRepositoryManager getGitRepositoryManager() {
    throw new UnsupportedOperationException("getGitRepositoryManager: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public Map<EventWrapper.Originator, Set<GerritPublishable>> getReplicatedProcessors() {
    throw new UnsupportedOperationException("getReplicatedProcessors: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public SchemaFactory<ReviewDb> getSchemaFactory() {
    throw new UnsupportedOperationException("getSchemaFactory: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public void subscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
    throw new UnsupportedOperationException("subscribeEvent: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public void unsubscribeEvent(EventWrapper.Originator eventType, GerritPublishable toCall) {
    throw new UnsupportedOperationException("unsubscribeEvent: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public boolean isCacheToBeEvicted(String cacheName) {
    return false;
  }

  @Override
  public ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingAccountIndexEventProcessor getReplicatedIncomingAccountIndexEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingAccountIndexEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingServerEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");

  }

  @Override
  public ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingCacheEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor() {
    throw new UnsupportedOperationException("getReplicatedIncomingProjectEventProcessor: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingIndexEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingCacheEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingProjectEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedOutgoingAccountIndexEventsFeed getReplicatedOutgoingAccountIndexEventsFeed() {
    throw new UnsupportedOperationException("getReplicatedOutgoingAccountIndexEventsFeed: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker() {
    throw new UnsupportedOperationException("getReplicatedIncomingEventWorker: Unable to get access to replicated objects when not using replicated entry points.");
  }


  @Override
  public ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker() {
    throw new UnsupportedOperationException("getReplicatedOutgoingEventWorker: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public ReplicatedScheduling getReplicatedScheduling() {
    throw new UnsupportedOperationException("getReplicatedScheduling: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public void queueEventForReplication(EventWrapper event) {
    throw new UnsupportedOperationException("queueEventForReplication: Unable to get access to replicated objects when not using replicated entry points.");
  }

  @Override
  public String getThisNodeIdentity() {
    return getReplicatedConfiguration().getThisNodeIdentity();
  }

}
