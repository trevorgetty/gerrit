package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.CacheObjectCallWrapper;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.ReplicatorMetrics;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * This class is to manage the replication of the cache events happening in
 * the original gerrit source code.
 * When a cache is used, it is registered with this class and then when
 * a cache eviction is performed, this eviction is replicated on the other nodes.
 * On the other nodes a reload can be issued too. This can be useful for
 * the web application loading data from the caches.
 * <p>
 * <p>
 * Gerrit cache is:
 * <code>
 * <p>
 * [gerrit@dger04 gitms-gerrit-longtests]$ ssh -p 29418 admin@dger03.qava.wandisco.com gerrit show-caches
 * Gerrit Code Review        2.10.2-31-g361cb34        now    10:04:59   EDT
 * uptime   13 days 22 hrs
 * <p>
 * Name                          |Entries              |  AvgGet |Hit Ratio|
 * |   Mem   Disk   Space|         |Mem  Disk|
 * --------------------------------+---------------------+---------+---------+
 * accounts                      | 13974               |   2.7ms | 99%     |
 * accounts_byemail              | 12115               |   2.9ms | 99%     |
 * accounts_byname               | 36864               |   1.4ms | 97%     |
 * adv_bases                     |                     |         |         |
 * changes                       |                     |  98.8ms |  0%     |
 * groups                        |  4071               |   1.4ms | 99%     |
 * groups_byinclude              |  1193               |   2.5ms | 93%     |
 * groups_byname                 |    92               |   5.4ms | 99%     |
 * groups_byuuid                 | 15236               |   1.1ms | 99%     |
 * groups_external               |     1               |  11.1ms | 99%     |
 * groups_members                |  4338               |   1.9ms | 99%     |
 * ldap_group_existence          |    23               |  73.7ms | 90%     |
 * ldap_groups                   |  4349               |  75.0ms | 94%     |
 * ldap_groups_byinclude         | 44136               |         | 98%     |
 * ldap_usernames                |   613               |   1.1ms | 92%     |
 * permission_sort               | 98798               |         | 99%     |
 * plugin_resources              |                     |         |  0%     |
 * project_list                  |     1               |    5.8s | 99%     |
 * projects                      |  7849               |   2.3ms | 99%     |
 * sshkeys                       |  7633               |   9.9ms | 99%     |
 * D change_kind                   | 16986 293432 130.14m| 103.1ms | 96%  98%|
 * D conflicts                     | 15885  51031  45.70m|         | 89%  90%|
 * D diff                          |     7 322355   1.56g|   8.7ms | 20%  99%|
 * D diff_intraline                |   576 304594 202.28m|   8.4ms | 23%  99%|
 * D git_tags                      |    47     58   2.10m|         | 38% 100%|
 * D web_sessions                  |       842300 341.13m|         |         |
 * <p>
 * SSH:    281  users, oldest session started   13 days 22 hrs ago
 * Tasks: 2889  total =   33 running +   2828 ready +   28 sleeping
 * Mem: 49.59g total = 15.06g used + 18.82g free + 15.70g buffers
 * 49.59g max
 * 8192 open files
 * <p>
 * Threads: 40 CPUs available, 487 threads
 * </code>
 */
@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedOutgoingCacheEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedOutgoingCacheEventsFeed.class);
  private static List<String> cacheEvictList = null;

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   *
   * @param eventsCoordinator
   */
  public ReplicatedOutgoingCacheEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingCacheEventsFeed.class);
  }

  public static List<String> getAllUsersCacheEvictList() {
    if (cacheEvictList != null) {
      return cacheEvictList;
    }
    cacheEvictList = new ArrayList<>(Arrays.asList("sshkeys", "accounts", "accounts_byname", "accounts_byemail",
        "groups", "groups_byinclude", "groups_byname", "groups_byuuid", "groups_external", "groups_members",
        "ldap_group_existence", "ldap_groups", "ldap_groups_byinclude", "ldap_usernames"));
    return cacheEvictList;
  }


  public void replicateEvictionFromCache(String cacheName, Object key) {
    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, key, replicatedEventsCoordinator.getThisNodeIdentity());
    EventWrapper eventWrapper;

    String allUsers = replicatedEventsCoordinator.getReplicatedConfiguration().getAllUsersName();

    try {
      log.debug("CACHE About to call replicated eviction from cache for : CacheName: [ {} ] , Key: [ {} ]", cacheName, key);

      //Set eventWrapper to the All-Projects EventWrapper initially
      eventWrapper = GerritEventFactory.createReplicatedAllProjectsCacheEvent(cacheKeyWrapper);

      // eventWrapper will be set to the All-Users cache event instead if its cache name exists in the All-Users
      // cache eviction list.
      if (getAllUsersCacheEvictList().contains(cacheName)) {
        //Block to force cache update to the All-Users repo so it is triggered in sequence after event that caused the eviction.
        log.debug("CACHE User replicated cache eviction All-Users Project CacheName: [ {} ], Key: [ {} ]", cacheName, key);
        eventWrapper = GerritEventFactory.createReplicatedCacheEvent(allUsers, cacheKeyWrapper);
      }

      replicatedEventsCoordinator.queueEventForReplication(eventWrapper);
      ReplicatorMetrics.addEvictionsSent(cacheName);
    } catch (IOException e) {
      log.error("Unable to create EventWrapper instance from replicated cache event : {}", e.getMessage());
    }
  }

  public void replicateMethodCallFromCache(String cacheName, String methodName, Object key) {
    CacheObjectCallWrapper cacheMehodCall = new CacheObjectCallWrapper(cacheName, methodName, key, replicatedEventsCoordinator.getThisNodeIdentity());
    log.info("CACHE About to call replicated cache method: {},{},{}", cacheName, methodName, key);
    try {
      replicatedEventsCoordinator.queueEventForReplication(
          GerritEventFactory.createReplicatedAllProjectsCacheEvent(cacheMehodCall));
    } catch (IOException e) {
      log.error("Unable to create EventWrapper instance from replicated cache event", e);
    }
  }

}
