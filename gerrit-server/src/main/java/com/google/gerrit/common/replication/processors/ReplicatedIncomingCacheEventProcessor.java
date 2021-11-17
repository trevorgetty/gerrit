package com.google.gerrit.common.replication.processors;

import com.google.common.cache.Cache;
import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.CacheObjectCallWrapper;
import com.google.gerrit.common.replication.ReplicatedCacheWrapper;
import com.google.gerrit.common.replication.ReplicatorMetrics;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
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
  private final Map<String, Object> cacheObjects = new ConcurrentHashMap<>();
  public static String projectCache = "ProjectCacheImpl";


  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   *
   * @param replicatedEventsCoordinator
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
  public void publishIncomingReplicatedEvents(EventWrapper newEvent) {
    if (newEvent.getEventOrigin() == EventWrapper.Originator.CACHE_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        CacheKeyWrapper originalEvent = null;

        try {
          originalEvent = (CacheKeyWrapper) gson.fromJson(newEvent.getEvent(), eventClass);
          if (originalEvent == null) {
            throw new JsonSyntaxException("Event Json Parsing returning no valid event information from: " + newEvent);
          }
        } catch (JsonSyntaxException je) {
          log.error("PR Could not decode json event {}", newEvent, je);
          throw new JsonSyntaxException(String.format("PR Could not decode json event %s", newEvent), je);
        }

        originalEvent.rebuildOriginal();
        log.debug("RE Original event: {}", originalEvent.toString());
        originalEvent.replicated = true; // not needed, but makes it clear
        originalEvent.setNodeIdentity(replicatedEventsCoordinator.getThisNodeIdentity());

        if (originalEvent instanceof CacheObjectCallWrapper) {
          CacheObjectCallWrapper originalObj = (CacheObjectCallWrapper) originalEvent;
          applyMethodCallOnCache(originalObj.cacheName, originalObj.key, originalObj.methodName);
        } else {
          applyReplicatedEvictionFromCache(originalEvent.cacheName, originalEvent.key);
        }
      } catch (ClassNotFoundException e) {
        final String err = String.format("WARNING: Unable to publish a replicated event using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
        log.warn(err);
        throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
      }
      return;
    }

    final String err = String.format("Event has been sent to CACHE_EVENT processor but originator is not the right one (%s)", newEvent);
    log.error(err);
    throw new ReplicatedEventsUnknownTypeException(err);
  }

  private void applyReplicatedEvictionFromCache(String cacheName, Object key) {
    boolean evicted = false;
    boolean reloaded = false;
    ReplicatedCacheWrapper wrapper = caches.get(cacheName);
    if (wrapper == null) {
      log.error("CACHE call could not be made, as cache does not exist. {}", cacheName);
      throw new ReplicatedEventsUnknownTypeException(
          String.format("CACHE call on replicated eviction could not be made, as cache does not exist. %s", cacheName));
    }

    if (replicatedEventsCoordinator.isCacheToBeEvicted(cacheName)) {
      log.debug("CACHE {} to evict {}...", cacheName, key);
      evicted = wrapper.evict(key);
      if (replicatedEventsCoordinator.getReplicatedConfiguration().isCacheToBeReloaded(cacheName)) {
        log.debug("CACHE {} to reload key {}...", cacheName, key);
        reloaded = wrapper.reload(key);
      } else {
        log.debug("CACHE {} *not* to reload key {}...", cacheName, key);
      }
    } else {
      log.debug("CACHE {} to *not* to evict {}...", cacheName, key);
    }

    if (evicted) {
      ReplicatorMetrics.addEvictionsPerformed(cacheName);
    }
    if (reloaded) {
      ReplicatorMetrics.addReloadsPerformed(cacheName);
    }
  }

  private void applyMethodCallOnCache(String cacheName, Object key, String methodName) {
    Object obj = cacheObjects.get(cacheName);
    if (obj == null) {
      // Failed to get a cache by the given name - return indicate failure - this wont change.
      log.error("CACHE method call could not be made, as cache does not exist. {}", cacheName);
      throw new ReplicatedEventsUnknownTypeException(
          String.format("CACHE call could not be made, as cache does not exist. %s", cacheName));
    }

    try {
      log.debug("Looking for method {}...", methodName);
      Method method = obj.getClass().getMethod(methodName, key.getClass());
      method.invoke(obj, key);
      log.debug("Success for {}!", methodName);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
      final String err = String.format("CACHE method call has been lost, could not call %s.%s", cacheName, methodName);
      log.error(err, ex);
      throw new ReplicatedEventsUnknownTypeException(err);
    }
  }

  public void watchCache(String cacheName, Cache cache) {
    caches.put(cacheName, new ReplicatedCacheWrapper(cache));
    log.info("CACHE New cache named {} inserted", cacheName);
  }

  public void watchObject(String cacheName, ProjectCache projectCache) {
    cacheObjects.put(cacheName, projectCache);
  }


}
