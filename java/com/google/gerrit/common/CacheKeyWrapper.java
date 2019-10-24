
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
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
  public long eventTimestamp;
  public String nodeIdentity;
  public transient boolean replicated = false;
  protected static final Gson gson = new Gson();

  public CacheKeyWrapper(String cacheName, Object key, String nodeIdentity) {
    this.cacheName = cacheName;
    this.key = key;
    this.keyClassName = key.getClass().getName();
    this.keyValue = gson.toJson(key);
    this.eventTimestamp = System.currentTimeMillis();
    this.nodeIdentity = nodeIdentity;
  }

  @Override
  public String toString() {
    return "CacheKeyWrapper{" +
        "cacheName='" + cacheName + '\'' +
        ", key=" + key +
        ", keyClassName='" + keyClassName + '\'' +
        ", keyValue='" + keyValue + '\'' +
        ", eventTimestamp=" + eventTimestamp +
        ", nodeIdentity='" + nodeIdentity + '\'' +
        ", replicated=" + replicated +
        '}';
  }

  public void setNodeIdentity(String nodeIdentity) {
    this.nodeIdentity = nodeIdentity;
  }

  void rebuildOriginal() throws ClassNotFoundException {
    Class<?> originalKeyClass = Class.forName(keyClassName);
    this.key = gson.fromJson(this.keyValue, originalKeyClass);
  }
}
