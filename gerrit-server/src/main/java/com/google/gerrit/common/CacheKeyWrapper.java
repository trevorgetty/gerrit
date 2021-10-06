
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

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

/**
 * This is a wrapper for the cache message to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 *
 */
public class CacheKeyWrapper extends ReplicatedEvent {
  public String cacheName;
  public Object key;
  public String keyClassName;
  public String keyValue;
  public transient boolean replicated = false;
  protected static final Gson gson = new Gson();

  public CacheKeyWrapper(String cacheName, Object key, String nodeIdentity) {
    super(nodeIdentity);
    this.cacheName = cacheName;
    this.key = key;
    this.keyClassName = key.getClass().getName();
    this.keyValue = gson.toJson(key);
  }

  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("CacheKeyWrapper{");
    sb.append("cacheName='").append(cacheName).append('\'');
    sb.append(", key=").append(key);
    sb.append(", keyClassName='").append(keyClassName).append('\'');
    sb.append(", keyValue='").append(keyValue).append('\'');
    sb.append(", ").append(super.toString()).append('\'');
    sb.append(", replicated=").append(replicated);
    sb.append('}');
    return sb.toString();
  }

  public void setNodeIdentity(String nodeIdentity) {
    super.setNodeIdentity(nodeIdentity);
  }

  public void rebuildOriginal() throws ClassNotFoundException {
    Class<?> originalKeyClass = Class.forName(keyClassName);
    this.key = gson.fromJson(this.keyValue, originalKeyClass);
  }
}
