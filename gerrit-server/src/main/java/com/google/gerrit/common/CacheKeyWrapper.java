package com.google.gerrit.common;

import com.google.gson.Gson;

/**
 * This is a wrapper for the cache message to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 *
 */
public class CacheKeyWrapper {
  public String cacheName;
  public Object key;
  public String keyClassName;
  public String keyValue;
  public transient boolean replicated = false;
  protected static final Gson gson = new Gson();

  public CacheKeyWrapper(String cacheName, Object key) {
    this.cacheName = cacheName;
    this.key = key;
    this.keyClassName = key.getClass().getName();
    this.keyValue = gson.toJson(key);
  }

  @Override
  public String toString() {
    return "CacheKeyWrapper{" + "cacheName=" + cacheName + ", key=" + key + ", keyClassName=" + keyClassName + ", keyValue="
        + keyValue + ", replicated=" + replicated + '}';
  }

  void rebuildOriginal() throws ClassNotFoundException {
    Class<?> originalKeyClass = Class.forName(keyClassName);
    this.key = gson.fromJson(this.keyValue, originalKeyClass);
  }
}
