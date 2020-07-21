
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
 
package com.google.gerrit.gerritconsoleapi.bindings;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.server.cache.CacheBackend;
import com.google.gerrit.server.cache.CacheBinding;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.cache.PersistentCacheFactory;

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.plugins.Plugin;
import com.google.inject.Inject;

import com.google.inject.Singleton;

import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class NonPersistanceCacheFactory implements PersistentCacheFactory, LifecycleListener {
  private static final Logger log =
      LoggerFactory.getLogger(NonPersistanceCacheFactory.class);


  @Inject
  NonPersistanceCacheFactory(
      @GerritServerConfig Config cfg,
      SitePaths site,
      DynamicMap<Cache<?, ?>> cacheMap) {

  }

  @Override
  public void start() {
    throw new RuntimeException(new NotSupportedException("NonPersistanceCache should not be called, use persist=false"));

  }

  @Override
  public void stop() {
    throw new RuntimeException(new NotSupportedException("NonPersistanceCache should not be called, use persist=false"));
  }

  @SuppressWarnings({"unchecked"})
  @Override
  public <K, V> Cache<K, V> build(PersistentCacheDef<K, V> def, CacheBackend backend) {
    return null;
  }

  @SuppressWarnings({"unchecked"})
  @Override
  public <K, V> LoadingCache<K, V> build(PersistentCacheDef<K, V> def, CacheLoader<K, V> loader, CacheBackend backend) {
    return null;
  }

  @Override
  public void onStop(String plugin) {
    throw new RuntimeException(new NotSupportedException("NonPersistanceCache should not be called, use persist=false"));
  }

}
