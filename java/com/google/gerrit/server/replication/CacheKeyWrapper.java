
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

import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.Objects;
import java.util.StringJoiner;

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

  @Override
  public boolean equals(Object o) {
    if (this == o) { return true; }
    if (!(o instanceof CacheKeyWrapper)) { return false; }

    CacheKeyWrapper that = (CacheKeyWrapper) o;

    if (replicated != that.replicated) { return false; }
    if (!Objects.equals(cacheName, that.cacheName)) { return false; }
    if (!Objects.equals(key, that.key)) { return false; }
    if (!Objects.equals(keyClassName, that.keyClassName)) { return false; }
    return Objects.equals(keyValue, that.keyValue);
  }

  @Override
  public int hashCode() {
    int result = cacheName != null ? cacheName.hashCode() : 0;
    result = 31 * result + (key != null ? key.hashCode() : 0);
    result = 31 * result + (keyClassName != null ? keyClassName.hashCode() : 0);
    result = 31 * result + (keyValue != null ? keyValue.hashCode() : 0);
    result = 31 * result + (replicated ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", CacheKeyWrapper.class.getSimpleName() + "[", "]")
            .add("cacheName='" + cacheName + "'")
            .add("key=" + key)
            .add("keyClassName='" + keyClassName + "'")
            .add("keyValue='" + keyValue + "'")
            .add("replicated=" + replicated)
            .add(super.toString())
            .toString();
  }

  void rebuildOriginal() throws ClassNotFoundException {
    Class<?> originalKeyClass = Class.forName(keyClassName);
    this.key = gson.fromJson(this.keyValue, originalKeyClass);
  }
}
