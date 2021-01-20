// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.index.group;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.GroupIndexedListener;
import com.google.gerrit.index.Index;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.group.InternalGroup;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.replication.ReplicatedAccountsIndexManager;
import com.google.gerrit.server.replication.Replicator;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public class GroupIndexerImpl implements GroupIndexer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public interface Factory {
    GroupIndexerImpl create(GroupIndexCollection indexes);

    GroupIndexerImpl create(@Nullable GroupIndex index);
  }

  private final GroupCache groupCache;
  private final PluginSetContext<GroupIndexedListener> indexedListener;
  private final StalenessChecker stalenessChecker;
  @Nullable private final ReplicatedAccountsIndexManager replicatedAccountsIndexManager;
  @Nullable private final GroupIndexCollection indexes;
  @Nullable private final GroupIndex index;

  @AssistedInject
  GroupIndexerImpl(
      GroupCache groupCache,
      PluginSetContext<GroupIndexedListener> indexedListener,
      StalenessChecker stalenessChecker,
      @Assisted GroupIndexCollection indexes) {
    this.groupCache = groupCache;
    this.indexedListener = indexedListener;
    this.stalenessChecker = stalenessChecker;
    this.indexes = indexes;
    this.index = null;
    this.replicatedAccountsIndexManager = ReplicatedAccountsIndexManager.Factory.create(this);
  }

  @AssistedInject
  GroupIndexerImpl(
      GroupCache groupCache,
      PluginSetContext<GroupIndexedListener> indexedListener,
      StalenessChecker stalenessChecker,
      @Assisted @Nullable GroupIndex index) {
    this.groupCache = groupCache;
    this.indexedListener = indexedListener;
    this.stalenessChecker = stalenessChecker;
    this.indexes = null;
    this.index = index;
    this.replicatedAccountsIndexManager = ReplicatedAccountsIndexManager.Factory.create(this);
  }


  @Override
  public void index(AccountGroup.UUID uuid) throws IOException {
    indexImplementation(uuid, Replicator.isReplicationEnabled());
  }


  /**
   * To allow an index to take place locally only, usually called by a handler in response to a replicated event,
   * so as to avoid a cyclic replication of the same event.
   * @param identifier
   * @throws IOException
   */
  @Override
  public void indexNoRepl(Serializable identifier) throws IOException {
    indexImplementation((AccountGroup.UUID) identifier, false);
  }

  public void indexImplementation(AccountGroup.UUID uuid, boolean replicate) throws IOException {
    // Evict the cache to get an up-to-date value for sure.
    groupCache.evict(uuid, replicate);
    Optional<InternalGroup> internalGroup = groupCache.get(uuid);

    if (internalGroup.isPresent()) {
      logger.atFine().log("Replace group %s in index", uuid.get());
    } else {
      logger.atFine().log("Delete group %s from index", uuid.get());
    }

    for (Index<AccountGroup.UUID, InternalGroup> i : getWriteIndexes()) {
      if (internalGroup.isPresent()) {
        try (TraceTimer traceTimer =
            TraceContext.newTimer(
                "Replacing group %s in index version %d", uuid.get(), i.getSchema().getVersion())) {
          i.replace(internalGroup.get());
        }
      } else {
        try (TraceTimer traceTimer =
            TraceContext.newTimer(
                "Deleting group %s in index version %d", uuid.get(), i.getSchema().getVersion())) {
          i.delete(uuid);
        }
      }
    }

    if (replicate && replicatedAccountsIndexManager != null) {
      replicatedAccountsIndexManager.replicateReindex(uuid);
    }

    fireGroupIndexedEvent(uuid.get());
  }

  @Override
  public boolean reindexIfStale(AccountGroup.UUID uuid) throws IOException {
    if (stalenessChecker.isStale(uuid)) {
      index(uuid);
      return true;
    }
    return false;
  }

  private void fireGroupIndexedEvent(String uuid) {
    indexedListener.runEach(l -> l.onGroupIndexed(uuid));
  }

  private Collection<GroupIndex> getWriteIndexes() {
    if (indexes != null) {
      return indexes.getWriteIndexes();
    }

    return index != null ? Collections.singleton(index) : ImmutableSet.of();
  }
}
