
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

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 *
 * @author antonio
 */
public class ReplicatedCacheManager implements Replicator.GerritPublishable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson gson = new Gson();
  private static final Map<String, CacheWrapper> caches = new HashMap<>();
  private static final Map<String, Object> cacheObjects = new HashMap<>();
  private static ReplicatedCacheManager instance = null;

  public static String projectCache = "ProjectCacheImpl";
  private static List<String> cacheEvictList = null;
  public static final String evictAllWildCard = "*";

  // Used for statistics
  private static final Multiset<String> evictionsPerformed = ConcurrentHashMultiset.create();
  private static final Multiset<String> evictionsSent = ConcurrentHashMultiset.create();
  private static final Multiset<String> reloadsPerformed = ConcurrentHashMultiset.create();

  private ReplicatedCacheManager() {
  }

  /**
   * Lazy initialising the cache eviction list. List
   * returned if not created already with a list of caches to
   * be evicted.
   * @return
   */
  public static List<String> getCacheEvictList(){
    if(cacheEvictList != null) {
      return cacheEvictList;
    }
    cacheEvictList = new ArrayList<>(Arrays.asList("sshkeys", "accounts", "accounts_byname", "accounts_byemail",
        "groups", "groups_byinclude", "groups_byname", "groups_byuuid", "groups_external", "groups_members",
        "groups_bysubgroup", "groups_bymember"));
    return cacheEvictList;
  }

  public static ImmutableMultiset<String> getEvictionsPerformed() {
    return ImmutableMultiset.copyOf(evictionsPerformed);
  }

  public static ImmutableMultiset<String> getEvictionsSent() {
    return ImmutableMultiset.copyOf(evictionsSent);
  }

  public static ImmutableMultiset<String> getReloadsPerformed() {
    return ImmutableMultiset.copyOf(reloadsPerformed);
  }

  static class CacheWrapper {
    Object cache;

    CacheWrapper(Object theCache) {
      this.cache = theCache;
    }

    private boolean evict(Object key) {
      boolean done = false;
      if (cache instanceof Cache) {
        if (key.toString().equals(evictAllWildCard)) {
          ((Cache) cache).invalidateAll();
        } else {
          ((Cache) cache).invalidate(key);
        }
        done = true;
      } else {
        logger.atSevere().withCause(new Exception("Class is missing: " + cache.getClass().getName())).log("CACHE is not supported!");
      }
      return done;
    }

    private boolean reload(Object key) {
      boolean done = false;
      if (cache instanceof LoadingCache) {
        try {
          Object obj = ((LoadingCache) cache).get(key);
          logger.atFine().log("%s loaded into the cache (1).", obj);
          done = true;
        } catch (Exception ex) {
          logger.atSevere().withCause(ex).log(
              "Error while trying to reload a key from the cache!");
        }
      } else if (cache instanceof Cache) {
        Object obj = ((Cache) cache).getIfPresent(key);
        logger.atFine().log("%s loaded into the cache (2).", obj);
        done = true;
      } else {
        logger.atSevere().withCause(new Exception(
            "Class is missing: " + cache.getClass().getName())).log("CACHE is not supported!");
      }
      return done;
    }

    @Override
    public int hashCode() {
      int hash = 7;
      hash = 97 * hash + Objects.hashCode(this.cache);
      return hash;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final CacheWrapper other = (CacheWrapper) obj;
      return Objects.equals(this.cache, other.cache);
    }
  }

  public static synchronized ReplicatedCacheManager watchCache(String cacheName, Cache cache) {
    if (Replicator.isReplicationDisabled()) {
      logger.atWarning().log("Replication is disabled, watchCache should not be getting called in this instance.");
      return null;
    }

    caches.put(cacheName, new CacheWrapper(cache));
    logger.atInfo().log("CACHE New cache named %s inserted", cacheName);
    if (instance == null) {
      instance = new ReplicatedCacheManager();
      Replicator.subscribeEvent(CACHE_EVENT, instance);
    }
    return instance;
  }

  public static void watchObject(String cacheName, ProjectCache projectCache) {
    cacheObjects.put(cacheName, projectCache);
  }

  public static void replicateEvictionFromCache(String cacheName, Object key) {
    if (Replicator.isReplicationDisabled()) {
      return;
    }

    if (key.toString().equals(evictAllWildCard)){
      logger.atInfo().log("CACHE key is %s so evicting all from cache: %s", evictAllWildCard, cacheName);
    }

    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, key, Replicator.getInstance().getThisNodeIdentity());
    EventWrapper eventWrapper = GerritEventFactory.createReplicatedAllProjectsCacheEvent(cacheKeyWrapper);
    logger.atFine().log("CACHE About to call replicated cache event: %s,%s", cacheName, key);

    //Block to force cache update to the All-Users repo so it is triggered in sequence after event that caused the eviction.
    if(getCacheEvictList().contains(cacheName)){
      logger.atFine().log("CACHE User Cache event setting update against All-Users Project %s,%s", cacheName, key);
      eventWrapper = GerritEventFactory.createReplicatedCacheEvent("All-Users", cacheKeyWrapper);
    }
    Replicator.getInstance().queueEventForReplication(eventWrapper);
    evictionsSent.add(cacheName);
  }

  /**
   * Replicate a method call from a cache to the other caches, make sure to use the
   * local only update on the remote nodes so as to avoid a recursive loop of event processing.
   *
   * @param cacheName
   * @param methodName
   * @param key
   */
  public static void replicateMethodCallFromCache(String cacheName, String methodName, Object key) {
    if (Replicator.isReplicationDisabled()) {
      return;
    }
    CacheObjectCallWrapper cacheMethodCall = new CacheObjectCallWrapper(cacheName, methodName, key);
    logger.atInfo().log("CACHE About to call replicated cache method: %s, %s, %s", cacheName, methodName, key);
    Replicator.getInstance().queueEventForReplication(
        GerritEventFactory.createReplicatedAllProjectsCacheEvent(cacheMethodCall));
    evictionsSent.add(cacheName);
  }

  private static void applyReplicatedEvictionFromCache(String cacheName, Object key) {
    boolean evicted = false;
    boolean reloaded = false;
    CacheWrapper wrapper = caches.get(cacheName);
    if (wrapper != null) {
      if (Replicator.isCacheToBeEvicted(cacheName)) {
        logger.atFine().log("CACHE %s to evict %s...", cacheName, key);
        evicted = wrapper.evict(key);
        // Only reload the key if the cache is to be reloaded and the key is not a wildcard
        if (Replicator.isCacheToBeReloaded(cacheName) && !key.toString().equals(evictAllWildCard)) {
          logger.atFine().log("CACHE %s to reload key %s...", cacheName, key);
          reloaded = wrapper.reload(key);
        } else {
          logger.atFine().log("CACHE %s *not* to reload key %s...", cacheName, key);
        }
      } else {
        logger.atFine().log("CACHE %s to *not* to evict %s...", cacheName, key);
      }
    } else {
      logger.atSevere().log("CACHE %s not found!", cacheName);
    }
    if (evicted) {
      evictionsPerformed.add(cacheName);
    }
    if (reloaded) {
      reloadsPerformed.add(cacheName);
    }
  }

  private static boolean applyMethodCallOnCache(String cacheName, Object key, String methodName) {
    boolean result = false;
    Object obj = cacheObjects.get(cacheName);
    if (obj != null) {
      try {
        logger.atFine().log("Looking for method %s...", methodName);
        Method method = obj.getClass().getMethod(methodName, key.getClass());
        method.invoke(obj, key);
        logger.atFine().log("Success for %s!", methodName);
        result = true;
      } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
        logger.atSevere().withCause(ex).log("CACHE method call has been lost, could not call %s.%s", cacheName, methodName);
      }
    }
    return result;
  }

  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.getEventOrigin() == CACHE_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        CacheKeyWrapper originalEvent = null;

        try {
          originalEvent = (CacheKeyWrapper) gson.fromJson(newEvent.getEvent(), eventClass);
        } catch (JsonSyntaxException e) {
          logger.atSevere().withCause(e).log("PR Could not decode json event {}", newEvent.toString());
          return result;
        }

        if (originalEvent == null) {
          logger.atSevere().log("fromJson method returned null for %s", newEvent.toString());
          return result;
        }

        originalEvent.rebuildOriginal();
        logger.atFine().log("RE Original event: %s", originalEvent.toString());
        originalEvent.replicated = true; // not needed, but makes it clear
        originalEvent.setNodeIdentity(Replicator.getInstance().getThisNodeIdentity());

        if (originalEvent instanceof CacheObjectCallWrapper) {
          CacheObjectCallWrapper originalObj = (CacheObjectCallWrapper) originalEvent;
          result = applyMethodCallOnCache(originalObj.cacheName, originalObj.key, originalObj.methodName);
        } else {
          applyReplicatedEvictionFromCache(originalEvent.cacheName, originalEvent.key);
          result = true;
        }
      } catch (ClassNotFoundException e) {
        logger.atSevere().withCause(e).log("CACHE event has been lost. Could not find {}", newEvent.getClassName());
      }
    } else if (newEvent != null && newEvent.getEventOrigin() != CACHE_EVENT) {
      logger.atSevere().log("CACHE event has been sent here but originartor is not the right one (%s)", newEvent.getEventOrigin());
    }
    return result;
  }

}
