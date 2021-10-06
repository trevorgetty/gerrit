package com.google.gerrit.common.replication;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class ReplicatedCacheWrapper {
  Object cache;
  private static final Logger log = LoggerFactory.getLogger(ReplicatedCacheWrapper.class);

  public ReplicatedCacheWrapper(Object theCache) {
    this.cache = theCache;
  }

  public boolean evict(Object key) {
    boolean done = false;
    if (cache instanceof Cache) {
      ((Cache) cache).invalidate(key);
      done = true;
    } else {
      log.error("CACHE is not supported!", new Exception("Class is missing: "+cache.getClass().getName()));
    }
    return done;
  }

  public boolean reload(Object key) {
    boolean done = false;
    if (cache instanceof LoadingCache) {
      try {
        Object obj = ((LoadingCache) cache).get(key);
        log.debug("{} loaded into the cache (1).",obj);
        done = true;
      } catch (Exception ex) {
        log.error("Error while trying to reload a key from the cache!", ex);
      }
    } else if (cache instanceof Cache) {
        Object obj = ((Cache) cache).getIfPresent(key);
        log.debug("{} loaded into the cache (2).",obj);
        done = true;
    } else {
      log.error("CACHE is not supported!", new Exception("Class is missing: "+cache.getClass().getName()));
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
    final ReplicatedCacheWrapper other = (ReplicatedCacheWrapper) obj;
    return Objects.equals(this.cache, other.cache);
  }
}
