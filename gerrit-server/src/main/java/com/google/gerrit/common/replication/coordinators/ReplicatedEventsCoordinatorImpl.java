package com.google.gerrit.common.replication.coordinators;

import com.google.common.base.Verify;
import com.google.gerrit.common.replication.ReplicatedScheduling;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingAccountIndexEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingCacheEventsFeed;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingProjectEventsFeed;
import com.google.gerrit.common.replication.processors.GerritPublishable;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingCacheEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingAccountIndexEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingProjectEventProcessor;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingServerEventProcessor;
import com.google.gerrit.common.replication.workers.ReplicatedIncomingEventWorker;
import com.google.gerrit.common.replication.processors.ReplicatedIncomingIndexEventProcessor;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingIndexEventsFeed;
import com.google.gerrit.common.replication.workers.ReplicatedOutgoingEventWorker;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is the coordinator responsible for the various threads in operation,
 * whether they be single processing threads like outgoing thread processor, or the new thread pool for incoming events processing.
 */
@Singleton
public class ReplicatedEventsCoordinatorImpl implements ReplicatedEventsCoordinator {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedEventsCoordinatorImpl.class);
  private final ReplicatedConfiguration replicatedConfiguration;
  private final ReplicatedIncomingIndexEventProcessor replicatedIncomingIndexEventProcessor;
  private final ReplicatedIncomingAccountIndexEventProcessor replicatedIncomingAccountIndexEventProcessor;
  private final ReplicatedIncomingServerEventProcessor replicatedIncomingServerEventProcessor;
  private final ReplicatedIncomingCacheEventProcessor replicatedIncomingCacheEventProcessor;
  private final ReplicatedIncomingProjectEventProcessor replicatedIncomingProjectEventProcessor;

  private final ReplicatedOutgoingIndexEventsFeed replicatedOutgoingIndexEventsFeed;
  private final ReplicatedOutgoingCacheEventsFeed replicatedOutgoingCacheEventsFeed;
  private final ReplicatedOutgoingProjectEventsFeed replicatedOutgoingProjectEventsFeed;

  private final ReplicatedOutgoingAccountIndexEventsFeed replicatedOutgoingAccountIndexEventsFeed;

  private final ReplicatedIncomingEventWorker replicatedIncomingEventWorker;
  private final ReplicatedOutgoingEventWorker replicatedOutgoingEventWorker;


  private final ReplicatedScheduling replicatedScheduling;

  private final Provider<SchemaFactory<ReviewDb>> schemaFactory;
  private final Provider<ChangeIndexer> changeIndexer;
  private final Provider<AccountIndexer> accountIndexer;
  private final GitRepositoryManager gitRepositoryManager;
  private final Gson gson;

  private Injector sysInjector;

  @Override
  public boolean isGerritIndexerRunning() {
    // Add in the WD replication disabled flag here in addition in 2.16!
    return !replicatedConfiguration.getConfigureReplication().isReplicationDisabled();
  }

  @Inject
  public ReplicatedEventsCoordinatorImpl(@GerritServerConfig Config config,
                                         ReplicatedConfiguration configuration,
                                         Provider<SchemaFactory<ReviewDb>> schemaFactory,
                                         Provider<ChangeIndexer> changeIndexer,
                                         Provider<AccountIndexer> accountIndexer,
                                         GitRepositoryManager gitRepositoryManager,
                                         @Named("wdGson") Gson gson) throws Exception {
    Verify.verifyNotNull(configuration);
    this.replicatedConfiguration = configuration;
    this.gerritVanillaServerConfig = config;
    this.schemaFactory = schemaFactory;
    this.changeIndexer = changeIndexer;
    this.accountIndexer = accountIndexer;
    this.gitRepositoryManager = gitRepositoryManager;
    this.gson = gson;

    ensureEventsDirectoriesExist();


    /* Workers */
    replicatedIncomingEventWorker = new ReplicatedIncomingEventWorker(this);
    replicatedOutgoingEventWorker = new ReplicatedOutgoingEventWorker(this);

    /* Processors
    *  Responsible for processing incoming events and actioning them*/
    replicatedIncomingIndexEventProcessor = new ReplicatedIncomingIndexEventProcessor(this);
    replicatedIncomingAccountIndexEventProcessor = new ReplicatedIncomingAccountIndexEventProcessor(this);
    replicatedIncomingServerEventProcessor = new ReplicatedIncomingServerEventProcessor(this);
    replicatedIncomingCacheEventProcessor = new ReplicatedIncomingCacheEventProcessor(this);
    replicatedIncomingProjectEventProcessor = new ReplicatedIncomingProjectEventProcessor(this);

    /*
     * Feeds to the queue.
     * Does not include ReplicatedOutgoingServerEventsFeed. It is a standalone feed
     * that does not require a member variable here as it is not accessed by any other class.
     */
    replicatedOutgoingIndexEventsFeed = new ReplicatedOutgoingIndexEventsFeed(this);
    replicatedOutgoingCacheEventsFeed = new ReplicatedOutgoingCacheEventsFeed(this);
    replicatedOutgoingProjectEventsFeed = new ReplicatedOutgoingProjectEventsFeed(this);
    replicatedOutgoingAccountIndexEventsFeed = new ReplicatedOutgoingAccountIndexEventsFeed(this);


    /** Creation of the new Scheduler which manages the Thread Pool of Incoming Processor,
     * as well as how and when to schedule work items to this pool.
     */
    replicatedScheduling = new ReplicatedScheduling(this);
  }

  @Override
  public Injector getSysInjector() {
    return sysInjector;
  }

  @Override
  public void setSysInjector(Injector sysInjector) {
    this.sysInjector = sysInjector;
  }

  @Override
  public void start() {
    // we can delay start if need be / checking other items.... only if we can't create something early because
    // of cyclic dependencies would I normally use this, or something that needs to wait for other threads to start.
    replicatedScheduling.startScheduledWorkers();
  }

  @Override
  public void stop() {
    // lets kill our processors.
    replicatedIncomingCacheEventProcessor.stop();
    replicatedIncomingAccountIndexEventProcessor.stop();
    replicatedIncomingProjectEventProcessor.stop();
    replicatedIncomingServerEventProcessor.stop();
    replicatedIncomingIndexEventProcessor.stop();

    // We could maybe start using shutdown / then awaitTermination but for now we don't care, pull the plug and we
    // will reschedule the work load correctly.
    replicatedScheduling.stop();
  }


  @Override
  public void queueEventForReplication(EventWrapper event) {
    getReplicatedOutgoingEventWorker().queueEventWithOutgoingWorker(event); // queue is unbound, no need to check for result
  }

  // Note this is not the same as the replicator configuration - this is a GerritServerConfig from vanilla gerrit.
  private static Config gerritVanillaServerConfig = null;

  // This can be set by our own testing, but mainly it is setup when gerrit has injected and created a ReplicatedIndex
  // or events manager with the GerritServerConfig member. It then updates this member, which is a lazy init.  When we
  // move over to guice binding remove this as its redundant and in fact can cause a race if left!
  static void setGerritVanillaServerConfig(Config config) {
    gerritVanillaServerConfig = config;
  }

  /**
   * If in the Gerrit Configuration file the cache value for memoryLimit is 0 then
   * it means that no cache is configured and we are not going to replicate this kind of events.
   * <p>
   * Example gerrit config file:
   * [cache "accounts"]
   * memorylimit = 0
   * disklimit = 0
   * [cache "accounts_byemail"]
   * memorylimit = 0
   * disklimit = 0
   * <p>
   * There is here a small probability of race condition due to the use of the static and the global
   * gerritConfig variable. But in the worst case, we can miss just one call (because once it's initialized
   * it's stable)
   *
   * @param cacheName
   * @return true is the cache is not disabled, i.e. the name does not show up in the gerrit config file with a value of 0 memoryLimit
   */
  public boolean isCacheToBeEvicted(String cacheName) {
    return !(gerritVanillaServerConfig != null && gerritVanillaServerConfig.getLong("cache", cacheName, "memoryLimit", 4096) == 0);
  }

  // Using a concurrent hash map to allow subscribers to add to the list, and not affect us while we are using that list
  // in other threads.  This is a dirty read YES, but it greatly reduces contention in an area where there should be.
  // The only case were a listener may register late, may be from a plugin or lazy context and there is nothing wrong with
  // us sending to the listeners that we started with and completing that list, and the next event to come in will use the new
  // list.
  private final static Map<EventWrapper.Originator, Set<GerritPublishable>> replicatedEventProcessorList =
      new ConcurrentHashMap<>();

  @Override
  public ChangeIndexer getChangeIndexer() {
    return changeIndexer.get();
  }

  @Override
  public AccountIndexer getAccountIndexer() {
    return accountIndexer.get();
  }

  @Override
  public ReplicatedConfiguration getReplicatedConfiguration() {
    return replicatedConfiguration;
  }

  @Override
  public Gson getGson() {
    return gson;
  }

  @Override
  public Map<EventWrapper.Originator, Set<GerritPublishable>> getReplicatedProcessors() {
    return replicatedEventProcessorList;
  }

  @Override
  public SchemaFactory<ReviewDb> getSchemaFactory() {
    return schemaFactory.get();
  }

  @Override
  public GitRepositoryManager getGitRepositoryManager() {
    return gitRepositoryManager;
  }

  @Override
  public String getThisNodeIdentity() {
    return replicatedConfiguration.getThisNodeIdentity();
  }

  @Override
  public void subscribeEvent(EventWrapper.Originator eventType,
                             GerritPublishable toCall) {
    synchronized (replicatedEventProcessorList) {
      Set<GerritPublishable> set = replicatedEventProcessorList.get(eventType);
      if (set == null) {
        set = new HashSet<>();
        replicatedEventProcessorList.put(eventType, set);
      }
      set.add(toCall);
      log.info("Subscriber added to {}", eventType);
    }
  }

  @Override
  public void unsubscribeEvent(EventWrapper.Originator eventType,
                               GerritPublishable toCall) {
    synchronized (replicatedEventProcessorList) {
      Set<GerritPublishable> set = replicatedEventProcessorList.get(eventType);
      if (set != null) {
        set.remove(toCall);
        log.info("Subscriber removed of type {}", eventType);
      }
    }
  }

  @Override
  public ReplicatedIncomingIndexEventProcessor getReplicatedIncomingIndexEventProcessor() {
    return replicatedIncomingIndexEventProcessor;
  }

  @Override
  public ReplicatedIncomingAccountIndexEventProcessor getReplicatedIncomingAccountIndexEventProcessor() {
    return replicatedIncomingAccountIndexEventProcessor;
  }

  @Override
  public ReplicatedIncomingServerEventProcessor getReplicatedIncomingServerEventProcessor() {
    return replicatedIncomingServerEventProcessor;
  }

  @Override
  public ReplicatedIncomingCacheEventProcessor getReplicatedIncomingCacheEventProcessor() {
    return replicatedIncomingCacheEventProcessor;
  }

  @Override
  public ReplicatedIncomingProjectEventProcessor getReplicatedIncomingProjectEventProcessor() {
    return replicatedIncomingProjectEventProcessor;
  }

  @Override
  public ReplicatedOutgoingIndexEventsFeed getReplicatedOutgoingIndexEventsFeed() {
    return replicatedOutgoingIndexEventsFeed;
  }

  @Override
  public ReplicatedOutgoingCacheEventsFeed getReplicatedOutgoingCacheEventsFeed() {
    return replicatedOutgoingCacheEventsFeed;
  }

  @Override
  public ReplicatedOutgoingAccountIndexEventsFeed getReplicatedOutgoingAccountIndexEventsFeed() {
    return replicatedOutgoingAccountIndexEventsFeed;
  }

  @Override
  public ReplicatedOutgoingProjectEventsFeed getReplicatedOutgoingProjectEventsFeed() {
    return replicatedOutgoingProjectEventsFeed;
  }

  @Override
  public ReplicatedIncomingEventWorker getReplicatedIncomingEventWorker() {
    return replicatedIncomingEventWorker;
  }

  @Override
  public ReplicatedOutgoingEventWorker getReplicatedOutgoingEventWorker() {
    return replicatedOutgoingEventWorker;
  }

  @Override
  public ReplicatedScheduling getReplicatedScheduling() {
    return replicatedScheduling;
  }

  private void ensureEventsDirectoriesExist() throws Exception {
    if (!replicatedConfiguration.getIncomingReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getIncomingReplEventsDirectory().mkdirs()) {
        log.error(
            "RE {} path cannot be created! Replicated events will not work!",
            replicatedConfiguration.getIncomingReplEventsDirectory().getAbsolutePath());
        throw new Exception("RE {} path cannot be created! Replicated events will not work!" +
            replicatedConfiguration.getIncomingReplEventsDirectory().getAbsolutePath());
      }

      log.info("RE {} incoming events location created.",
          replicatedConfiguration.getIncomingReplEventsDirectory().getAbsolutePath());
    }
    if (!replicatedConfiguration.getOutgoingReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getOutgoingReplEventsDirectory().mkdirs()) {
        log.error(
            "RE {} path cannot be created! Replicated outgoing events will not work!",
            replicatedConfiguration.getOutgoingReplEventsDirectory().getAbsolutePath());
        throw new Exception("RE {} path cannot be created! Replicated outgoing events will not work!" +
            replicatedConfiguration.getOutgoingReplEventsDirectory().getAbsolutePath());
      }

      log.info("RE {} outgoing events location created.",
          replicatedConfiguration.getOutgoingReplEventsDirectory().getAbsolutePath());
    }
    if (!replicatedConfiguration.getIncomingFailedReplEventsDirectory().exists()) {
      if (!replicatedConfiguration.getIncomingFailedReplEventsDirectory().mkdirs()) {
        log.error(
            "RE {} path cannot be created! Replicated failed events cannot be saved!",
            replicatedConfiguration.getIncomingFailedReplEventsDirectory().getAbsolutePath());
        throw new Exception("RE {} path cannot be created! Replicated failed events will not work!" +
            replicatedConfiguration.getIncomingFailedReplEventsDirectory().getAbsolutePath());
      }

      log.info("RE {} incoming failed events location created.",
          replicatedConfiguration.getIncomingFailedReplEventsDirectory().getAbsolutePath());
    }
  }


}
