package com.google.gerrit.common.replication.coordinators;

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
import com.google.gerrit.common.replication.ReplicatedScheduling;
import com.google.gerrit.common.replication.workers.ReplicatedIncomingEventWorker;
import com.google.gerrit.common.replication.workers.ReplicatedOutgoingEventWorker;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gwtorm.server.SchemaFactory;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.util.Map;
import java.util.Set;

public interface ReplicatedEventsCoordinator extends SysInjectable, LifecycleListener {

  boolean isGerritIndexerRunning();

  ChangeIndexer getChangeIndexer();

  AccountIndexer getAccountIndexer();

  String getThisNodeIdentity();

  ReplicatedConfiguration getReplicatedConfiguration();

  Gson getGson();

  GitRepositoryManager getGitRepositoryManager();

  Map<EventWrapper.Originator, Set<GerritPublishable>> getReplicatedProcessors();

  SchemaFactory<ReviewDb> getSchemaFactory();

  void subscribeEvent(EventWrapper.Originator eventType,
                      GerritPublishable toCall);

  void unsubscribeEvent(EventWrapper.Originator eventType,
                        GerritPublishable toCall);

  boolean isCacheToBeEvicted(String cacheName);

  void queueEventForReplication(EventWrapper event);

  ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor();

  ReplicatedIncomingAccountIndexEventProcessor getReplicatedIncomingAccountIndexEventProcessor();

  ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor();

  ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor();

  ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor();

  ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed();

  ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed();

  ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed();

  ReplicatedOutgoingAccountIndexEventsFeed getReplicatedOutgoingAccountIndexEventsFeed();

  ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker();

  ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker();

  ReplicatedScheduling getReplicatedScheduling();

}
