package com.google.gerrit.common.replication.processors;

import com.google.common.cache.Cache;
import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.CacheObjectCallWrapper;
import com.google.gerrit.common.replication.ConfigureReplication;
import com.google.gerrit.common.replication.ReplicatedCacheWrapper;
import com.google.gerrit.common.replication.ReplicatorMetrics;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;

public class ReplicatedIncomingCacheEventProcessor extends GerritPublishableImpl {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedIncomingCacheEventProcessor.class);
  private final Map<String, ReplicatedCacheWrapper> caches = new ConcurrentHashMap<>();
  private final Map<String,Object> cacheObjects = new ConcurrentHashMap<>();
  public static String projectCache = "ProjectCacheImpl";


  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   * @param eventsCoordinator
   */
  public ReplicatedIncomingCacheEventProcessor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(CACHE_EVENT, replicatedEventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingCacheEventProcessor.class);
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.getEventOrigin() ==  EventWrapper.Originator.CACHE_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        CacheKeyWrapper originalEvent = null;

        try {
          originalEvent = (CacheKeyWrapper) gson.fromJson(newEvent.getEvent(), eventClass);
        } catch (JsonSyntaxException je) {
          log.error("PR Could not decode json event {}", newEvent.toString(), je);
          return result;
        }

        if (originalEvent == null) {
          log.error("fromJson method returned null for {}", newEvent.toString());
          return result;
        }

        originalEvent.rebuildOriginal();
        log.debug("RE Original event: {}",originalEvent.toString());
        originalEvent.replicated = true; // not needed, but makes it clear
        originalEvent.setNodeIdentity(replicatedEventsCoordinator.getThisNodeIdentity());

        if (originalEvent instanceof CacheObjectCallWrapper) {
          CacheObjectCallWrapper originalObj = (CacheObjectCallWrapper) originalEvent;
          result = applyMethodCallOnCache(originalObj.cacheName,originalObj.key,originalObj.methodName);
        } else {
          applyReplicatedEvictionFromCache(originalEvent.cacheName,originalEvent.key);
          result = true;
        }
      } catch(ClassNotFoundException e) {
        log.error("CACHE event has been lost. Could not find {}",newEvent.getClassName(),e);
      }
    } else if (newEvent != null && newEvent.getEventOrigin() !=  EventWrapper.Originator.CACHE_EVENT) {
      log.error("CACHE event has been sent here but originator is not the right one ({})",newEvent.getEventOrigin());
    }
    return result;
  }

  private void applyReplicatedEvictionFromCache(String cacheName, Object key) {
    boolean evicted = false;
    boolean reloaded = false;
    ReplicatedCacheWrapper wrapper = caches.get(cacheName);
    if (wrapper != null) {
      if (replicatedEventsCoordinator.isCacheToBeEvicted(cacheName)) {
        log.debug("CACHE {} to evict {}...", cacheName,key);
        evicted = wrapper.evict(key);
        if (replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeReloaded(cacheName)) {
          log.debug("CACHE {} to reload key {}...",cacheName,key);
          reloaded = wrapper.reload(key);
        } else {
          log.debug("CACHE {} *not* to reload key {}...",cacheName,key);
        }
      } else {
        log.debug("CACHE {} to *not* to evict {}...",cacheName,key);
      }
    } else {
      log.error("CACHE {} not found!",cacheName);
    }
    if (evicted) {
      ReplicatorMetrics.addEvictionsPerformed(cacheName);
    }
    if (reloaded) {
      ReplicatorMetrics.addReloadsPerformed(cacheName);
    }
  }

  private boolean applyMethodCallOnCache(String cacheName,Object key,String methodName) {
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
        log.error("CACHE method call has been lost, could not call {}.{}",cacheName,methodName,ex);
      }
    }
    return result;
  }

  public void watchCache(String cacheName, Cache cache) {
    caches.put(cacheName, new ReplicatedCacheWrapper(cache));
    log.info("CACHE New cache named {} inserted",cacheName);
  }

  public void watchObject(String cacheName, ProjectCache projectCache) {
    cacheObjects.put(cacheName, projectCache);
  }


}
