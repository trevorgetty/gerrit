package com.google.gerrit.common;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;
import com.google.gerrit.server.events.ChangeEventWrapper;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gson.Gson;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is to manage the replication of the cache events happening in
 * the original gerrit source code.
 * When a cache is used, it is registered with this class and then when
 * a cache eviction is performed, this eviction is replicated on the other nodes.
 * On the other nodes a reload can be issued too. This can be useful for
 * the web application loading data from the caches.
 * 
 * 
 * Gerrit cache is:
 * <code>
  
    [gerrit@dger04 gitms-gerrit-longtests]$ ssh -p 29418 admin@dger03.qava.wandisco.com gerrit show-caches
      Gerrit Code Review        2.10.2-31-g361cb34        now    10:04:59   EDT
                                                       uptime   13 days 22 hrs

        Name                          |Entries              |  AvgGet |Hit Ratio|
                                      |   Mem   Disk   Space|         |Mem  Disk|
      --------------------------------+---------------------+---------+---------+
        accounts                      | 13974               |   2.7ms | 99%     |
        accounts_byemail              | 12115               |   2.9ms | 99%     |
        accounts_byname               | 36864               |   1.4ms | 97%     |
        adv_bases                     |                     |         |         |
        changes                       |                     |  98.8ms |  0%     |
        groups                        |  4071               |   1.4ms | 99%     |
        groups_byinclude              |  1193               |   2.5ms | 93%     |
        groups_byname                 |    92               |   5.4ms | 99%     |
        groups_byuuid                 | 15236               |   1.1ms | 99%     |
        groups_external               |     1               |  11.1ms | 99%     |
        groups_members                |  4338               |   1.9ms | 99%     |
        ldap_group_existence          |    23               |  73.7ms | 90%     |
        ldap_groups                   |  4349               |  75.0ms | 94%     |
        ldap_groups_byinclude         | 44136               |         | 98%     |
        ldap_usernames                |   613               |   1.1ms | 92%     |
        permission_sort               | 98798               |         | 99%     |
        plugin_resources              |                     |         |  0%     |
        project_list                  |     1               |    5.8s | 99%     |
        projects                      |  7849               |   2.3ms | 99%     |
        sshkeys                       |  7633               |   9.9ms | 99%     |
      D change_kind                   | 16986 293432 130.14m| 103.1ms | 96%  98%|
      D conflicts                     | 15885  51031  45.70m|         | 89%  90%|
      D diff                          |     7 322355   1.56g|   8.7ms | 20%  99%|
      D diff_intraline                |   576 304594 202.28m|   8.4ms | 23%  99%|
      D git_tags                      |    47     58   2.10m|         | 38% 100%|
      D web_sessions                  |       842300 341.13m|         |         |

      SSH:    281  users, oldest session started   13 days 22 hrs ago
      Tasks: 2889  total =   33 running +   2828 ready +   28 sleeping
      Mem: 49.59g total = 15.06g used + 18.82g free + 15.70g buffers
           49.59g max
              8192 open files

      Threads: 40 CPUs available, 487 threads
</code>
 *
 * @author antonio
 */
public class ReplicatedCacheManager implements Replicator.GerritPublishable {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedCacheManager.class);
  private static final Gson gson = new Gson();
  private static final Map<String,CacheWrapper> caches = new HashMap<>();
  private static final Map<String,Object> cacheObjects = new HashMap<>();
  private static ReplicatedCacheManager instance = null;
  
  public static String projectCache = "ProjectCacheImpl";
  
  // Used for statistics
  private static final Multiset<String> evictionsPerformed = ConcurrentHashMultiset.create();
  private static final Multiset<String> evictionsSent      = ConcurrentHashMultiset.create();
  private static final Multiset<String> reloadsPerformed   = ConcurrentHashMultiset.create();

  private ReplicatedCacheManager() {
    
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
        ((Cache) cache).invalidate(key);
        done = true;
      } else {
        log.error("CACHE is not supported!", new Exception("Class is missing"));
      }
      return done;
    }

    private boolean reload(Object key) {
      boolean done = false;
      if (cache instanceof LoadingCache) {
        try {
          Object obj = ((LoadingCache) cache).get(key);
          log.debug("{} loaded into the cache.",obj);
          done = true;
        } catch (Exception ex) {
          log.error("Error while trying to reload a key from the cache!", ex);
        }
      } else {
        log.error("CACHE is not supported!", new Exception("Class is missing"));
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

  public static synchronized ReplicatedCacheManager watchCache(String cacheName,Cache cache) {
    caches.put(cacheName, new CacheWrapper(cache));
    log.info("CACHE New cache named {} inserted",cacheName);
    if (instance == null) {
      instance = new ReplicatedCacheManager();
      Replicator.subscribeEvent(ChangeEventWrapper.Originator.CACHE_EVENT, instance);
    }
    return instance;
  } 

  public static void watchObject(String cacheName,ProjectCache projectCache) {
    cacheObjects.put(cacheName, projectCache);
  }
  
  public static void replicateEvictionFromCache(String cacheName, Object key) {
    CacheKeyWrapper cacheKeyWrapper = new CacheKeyWrapper(cacheName, key);
    log.debug("CACHE About to call replicated cache event: {},{}",new Object[] {cacheName,key});
    Replicator.getInstance().queueEventForReplication(new ChangeEventWrapper(cacheKeyWrapper));
    evictionsSent.add(cacheName);
  }

  public static void replicateMethodCallFromCache(String cacheName, String methodName, Object key) {
    CacheObjectCallWrapper cacheMehodCall = new CacheObjectCallWrapper(cacheName, methodName, key);
    log.info("CACHE About to call replicated cache method: {},{},{}",new Object[] {cacheName,methodName,key});
    Replicator.getInstance().queueEventForReplication(new ChangeEventWrapper(cacheMehodCall));
    evictionsSent.add(cacheName);
  }

  private static void applyReplicatedEvictionFromCache(String cacheName, Object key) {
    boolean evicted = false;
    boolean reloaded = false;
    CacheWrapper wrapper = caches.get(cacheName);
    if (wrapper != null) {
      if (Replicator.isCacheToBeEvicted(cacheName)) {
        log.debug("CACHE {} to evict {}...",new Object[] {cacheName,key});
        evicted = wrapper.evict(key);
        if (Replicator.isCacheToBeReloaded(cacheName)) {
          log.debug("CACHE {} to reload key {}...",new Object[] {cacheName,key});
          reloaded = wrapper.reload(key);
        } else {
          log.debug("CACHE {} *not* to reload key {}...",new Object[] {cacheName,key});
        }
      } else {
        log.debug("CACHE {} to *not* to evict {}...",new Object[] {cacheName,key});
      }
    } else {
      log.error("CACHE {} not found!",cacheName);
    }
    if (evicted) {
      evictionsPerformed.add(cacheName);
    }
    if (reloaded) {
      reloadsPerformed.add(cacheName);
    }
  }
  
  private static boolean applyMethodCallOnCache(String cacheName,Object key,String methodName) {
    boolean result = false;
    Object obj = cacheObjects.get(cacheName);
    if (obj != null)  {
      try {
        log.debug("Looking for method {}...",methodName);
        Method method = obj.getClass().getMethod(methodName, key.getClass());
        method.invoke(obj, key);
        log.debug("Success for {}!",methodName);
        result = true;
      } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
        log.error("CACHE method call has been lost, could not call {}.{}",new Object[] {cacheName,methodName},ex);
      }
    }
    return result;
  }

  @Override
  public boolean publishIncomingReplicatedEvents(ChangeEventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.originator ==  ChangeEventWrapper.Originator.CACHE_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.className);
        CacheKeyWrapper originalEvent = (CacheKeyWrapper) gson.fromJson(newEvent.changeEvent, eventClass);
        originalEvent.rebuildOriginal();
        log.debug("RE Original event: {}",originalEvent.toString());
        originalEvent.replicated = true; // not needed, but makes it clear
        if (originalEvent instanceof CacheObjectCallWrapper) {
          CacheObjectCallWrapper originalObj = (CacheObjectCallWrapper) originalEvent;
          result = applyMethodCallOnCache(originalObj.cacheName,originalObj.key,originalObj.methodName);
        } else {
          applyReplicatedEvictionFromCache(originalEvent.cacheName,originalEvent.key);
          result = true;
        }
      } catch(ClassNotFoundException e) {
        log.error("CACHE event has been lost. Could not find {}",newEvent.className,e);
      }
    }
    return result;
  }
}
