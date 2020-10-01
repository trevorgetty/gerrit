
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
 
package com.google.gerrit.server.replication;

/**
 * This is a wrapper for the cache method call to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 *
 */
public class CacheObjectCallWrapper extends CacheKeyWrapper {
  public String methodName;

  public CacheObjectCallWrapper(String cacheName, String method, Object key) {
    super(cacheName,key, Replicator.getInstance().getThisNodeIdentity());
    this.methodName = method;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("CacheObjectCallWrapper{");
    sb.append("methodName='").append(methodName).append('\'');
    sb.append(", ").append("[").append(super.toString()).append("]").append('\'');
    sb.append('}');
    return sb.toString();
  }

}
