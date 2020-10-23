// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.server.index.change;

import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.gerrit.server.git.QueueProvider.QueueType.BATCH;

import com.google.common.base.Objects;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.Atomics;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.index.Index;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.index.IndexExecutor;
import com.google.gerrit.server.index.IndexUtils;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.NotesMigration;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.replication.ReplicatedIndexEventManager;
import com.google.gerrit.server.replication.Replicator;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.OutOfScopeException;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.util.Providers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;

/**
 * Helper for (re)indexing a change document.
 *
 * <p>Indexing is run in the background, as it may require substantial work to compute some of the
 * fields and/or update the index.
 */
public class ChangeIndexer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public interface Factory {
    ChangeIndexer create(ListeningExecutorService executor, ChangeIndex index);

    ChangeIndexer create(ListeningExecutorService executor, ChangeIndexCollection indexes);
  }

  @SuppressWarnings("deprecation")
  public static com.google.common.util.concurrent.CheckedFuture<?, IOException> allAsList(
      List<? extends ListenableFuture<?>> futures) {
    // allAsList propagates the first seen exception, wrapped in
    // ExecutionException, so we can reuse the same mapper as for a single
    // future. Assume the actual contents of the exception are not useful to
    // callers. All exceptions are already logged by IndexTask.
    return Futures.makeChecked(Futures.allAsList(futures), IndexUtils.MAPPER);
  }

  @Nullable
  private final ChangeIndexCollection indexes;
  @Nullable
  private final ChangeIndex index;
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final NotesMigration notesMigration;
  private final ChangeNotes.Factory changeNotesFactory;
  private final ChangeData.Factory changeDataFactory;
  private final ThreadLocalRequestContext context;
  private final ListeningExecutorService batchExecutor;
  private final ListeningExecutorService executor;
  private final PluginSetContext<ChangeIndexedListener> indexedListeners;
  private final StalenessChecker stalenessChecker;
  private final boolean autoReindexIfStale;
  private final boolean replicateAutoReindexIfStale;

  private final Set<IndexTask> queuedIndexTasks =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final Set<ReindexIfStaleTask> queuedReindexIfStaleTasks =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  @AssistedInject
  ChangeIndexer(
      @GerritServerConfig Config cfg,
      SchemaFactory<ReviewDb> schemaFactory,
      NotesMigration notesMigration,
      ChangeNotes.Factory changeNotesFactory,
      ChangeData.Factory changeDataFactory,
      ThreadLocalRequestContext context,
      PluginSetContext<ChangeIndexedListener> indexedListeners,
      StalenessChecker stalenessChecker,
      @IndexExecutor(BATCH) ListeningExecutorService batchExecutor,
      @Assisted ListeningExecutorService executor,
      @Assisted ChangeIndex index) {
    this.executor = executor;
    this.schemaFactory = schemaFactory;
    this.notesMigration = notesMigration;
    this.changeNotesFactory = changeNotesFactory;
    this.changeDataFactory = changeDataFactory;
    this.context = context;
    this.indexedListeners = indexedListeners;
    this.stalenessChecker = stalenessChecker;
    this.batchExecutor = batchExecutor;
    this.autoReindexIfStale = autoReindexIfStale(cfg);
    this.replicateAutoReindexIfStale = replicateAutoReindexIfStale(cfg);
    this.index = index;
    this.indexes = null;
  }

  @AssistedInject
  ChangeIndexer(
      SchemaFactory<ReviewDb> schemaFactory,
      @GerritServerConfig Config cfg,
      NotesMigration notesMigration,
      ChangeNotes.Factory changeNotesFactory,
      ChangeData.Factory changeDataFactory,
      ThreadLocalRequestContext context,
      PluginSetContext<ChangeIndexedListener> indexedListeners,
      StalenessChecker stalenessChecker,
      @IndexExecutor(BATCH) ListeningExecutorService batchExecutor,
      @Assisted ListeningExecutorService executor,
      @Assisted ChangeIndexCollection indexes) {
    this.executor = executor;
    this.schemaFactory = schemaFactory;
    this.notesMigration = notesMigration;
    this.changeNotesFactory = changeNotesFactory;
    this.changeDataFactory = changeDataFactory;
    this.context = context;
    this.indexedListeners = indexedListeners;
    this.stalenessChecker = stalenessChecker;
    this.batchExecutor = batchExecutor;
    this.autoReindexIfStale = autoReindexIfStale(cfg);
    this.replicateAutoReindexIfStale = replicateAutoReindexIfStale(cfg);
    this.index = null;
    this.indexes = indexes;
  }

  private static boolean autoReindexIfStale(Config cfg) {
    return cfg.getBoolean("index", null, "autoReindexIfStale", false);
  }

  private static boolean replicateAutoReindexIfStale(Config cfg) {
    return cfg.getBoolean("index", null, "replicateAutoReindexIfStale", false);
  }

  /**
   * Start indexing a change, with replication to other sites enabled
   *
   * @param id change to index.
   * @return future for the indexing task.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsync(
      Project.NameKey project, Change.Id id) {

    // Default behaviour is to call with replication enabled
    return indexAsyncImpl(project, id, Replicator.isReplicationEnabled());
  }

  /**
   * Start indexing a change. Local only so without any replication, this enables this to be called from within the
   * replicator handlers without causing a recursive loop.
   *
   * @param id change to index.
   * @return future for the indexing task.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsyncNoRepl(
      Project.NameKey project, Change.Id id) {

    // Default behaviour is to call with replication disabled.
    return indexAsyncImpl(project, id, false);
  }

  /**
   * Start indexing a change.  This is real implementation used  by both the replicated and local only indexAsync
   * methods.
   * N.B. Private to ensure callers do not use this directly, but instead consider whether they wish to have replication
   * enabled or not, by calling the parent methods.
   *
   * @param id change to index.
   * @return future for the indexing task.
   */
  @SuppressWarnings("deprecation")
  private com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsyncImpl(
      Project.NameKey project, Change.Id id, boolean replicationEnabled) {
    logger.atFine().log("RC Going ASYNC to index %s replication: %s", id, lazy(() -> getReplicationString(replicationEnabled)));
    IndexTask task = new IndexTask(project, id, replicationEnabled);
    if (queuedIndexTasks.add(task)) {
      return submit(task);
    }
    return Futures.immediateCheckedFuture(null);
  }

  /**
   * Start indexing multiple changes in parallel.
   * With replication enabled by default!
   *
   * @param ids changes to index.
   * @return future for completing indexing of all changes.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsync(
      Project.NameKey project, Collection<Change.Id> ids) {
    return indexAsyncImpl(project, ids, Replicator.isReplicationEnabled());
  }

  /**
   * Start indexing multiple changes in parallel.
   * With replication disabled to allow this change to be local only, usually called from the replication handlers,
   * and avoid replication recursive loops.
   *
   * @param ids changes to index.
   * @return future for completing indexing of all changes.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsyncNoRepl(
      Project.NameKey project, Collection<Change.Id> ids) {
    return indexAsyncImpl(project, ids, false);
  }

  /**
   * Start indexing multiple changes in parallel.  This is the real implementation which allows the
   * indexing to be performed with or without replication.
   *
   * N.B. Private to ensure callers do not use this directly, but instead consider whether they wish to have replication
   * enabled or not, by calling the parent methods.
   *
   * @param project            project name which the index is for.
   * @param ids                changes to index.
   * @param replicationEnabled whether or not to replicate these changes to other nodes.
   * @return future for completing indexing of all changes.
   */
  @SuppressWarnings("deprecation")
  private com.google.common.util.concurrent.CheckedFuture<?, IOException> indexAsyncImpl(
      Project.NameKey project, Collection<Change.Id> ids, boolean replicationEnabled) {

    List<ListenableFuture<?>> futures = new ArrayList<>(ids.size());
    for (Change.Id id : ids) {
      logger.atFiner().log("RC Going ASYNC to index (from collection) %s replication: %s", id, lazy(() -> getReplicationString(replicationEnabled)));
      futures.add(indexAsyncImpl(project, id, replicationEnabled));
    }
    return allAsList(futures);
  }

  /**
   * Simply util method to return boolean of replicationEnabled as a string for debugging.   Wrap in a method to easily
   * reduce logging cost by using lazy() operator in fluent logging. see: https://google.github.io/flogger/best_practice
   * @param replicationEnabled
   * @return
   */
  private final static String getReplicationString(boolean replicationEnabled) {
    return replicationEnabled ? "REPLICATION" : "NO_REPLICATION";
  }

  /**
   * Synchronously index a change, then check if the index is stale due to a race condition.
   * This will index with replication enabled.
   *
   * @param cd change to index.
   */
  public void index(ChangeData cd) throws IOException {
    index(cd, Replicator.isReplicationEnabled());
  }

  /**
   * Synchronously index a change, then check if the index is stale due to a race condition.
   * This will index with replication disabled.
   *
   * @param cd change to index.
   */
  public void indexNoRepl(ChangeData cd) throws IOException {
    index(cd, false);
  }

  /**
   * Synchronously index a change, then check if the index is stale due to a race condition.
   * This will index with replication optionally enabled, this allow it to be called from within the replicator
   * handlers themselves and avoid replication recursive loops.
   *
   * @param cd change to index.
   */
  private void index(ChangeData cd, boolean replicationEnabled) throws IOException {
    indexImpl(cd, replicationEnabled);

    // Always double-check whether the change might be stale immediately after
    // interactively indexing it. This fixes up the case where two writers write
    // to the primary storage in one order, and the corresponding index writes
    // happen in the opposite order:
    //  1. Writer A writes to primary storage.
    //  2. Writer B writes to primary storage.
    //  3. Writer B updates index.
    //  4. Writer A updates index.
    //
    // Without the extra reindexIfStale step, A has no way of knowing that it's
    // about to overwrite the index document with stale data. It doesn't work to
    // have A check for staleness before attempting its index update, because
    // B's index update might not have happened when it does the check.
    //
    // With the extra reindexIfStale step after (3)/(4), we are able to detect
    // and fix the staleness. It doesn't matter which order the two
    // reindexIfStale calls actually execute in; we are guaranteed that at least
    // one of them will execute after the second index write, (4).
    autoReindexIfStale(cd);
  }

  private void indexImpl(ChangeData cd, boolean replicationEnabled) throws IOException {
    logger.atFine().log("Replace change %d in index.", cd.getId().get());
    for (Index<?, ChangeData> i : getWriteIndexes()) {
      try (TraceTimer traceTimer =
               TraceContext.newTimer(
                   "Replacing change %d in index version %d",
                   cd.getId().get(), i.getSchema().getVersion())) {
        i.replace(cd);
      }
    }
    fireChangeIndexedEvent(cd.project().get(), cd.getId().get());

    // If we have disabled replication either because:
    // 1) Its not a replicated call, so its supplied as false.
    // 2) replication is disabled systemwide by the disable env flag - again we will get replicationEnabled=false
    // 3) We are not called from the main Daemon context ( ReplicatedIndexEventManager will be null )
    // either way go no further and Dont replicate these changes.
    if (replicationEnabled == false || ( ReplicatedIndexEventManager.getInstance() == null )) {
      return;
    }

    // otherwise replicated these index changes now.
    try {
      Change change = cd.change();
      logger.atFine().log ("RC Finished SYNC index %d, queuing for replication...", change.getId().get());
      ReplicatedIndexEventManager.queueReplicationIndexEvent(change.getId().get(), change.getProject().get(), change.getLastUpdatedOn());
    } catch (OrmException e) {
      logger.atSevere().withCause(e).log("RC Could not sync'ly reindex change! EVENT LOST %s", cd);
    }
  }

  private void fireChangeIndexedEvent(String projectName, int id) {
    indexedListeners.runEach(l -> l.onChangeIndexed(projectName, id));
  }

  private void fireChangeDeletedFromIndexEvent(int id) {
    indexedListeners.runEach(l -> l.onChangeDeleted(id));
  }

  /**
   * Synchronously index a change.
   *
   * @param db     review database.
   * @param change change to index.
   */
  public void index(ReviewDb db, Change change) throws IOException, OrmException {
    index(newChangeData(db, change), true);
  }

  /**
   * Synchronously index a change.
   *
   * @param db       review database.
   * @param project  the project to which the change belongs.
   * @param changeId ID of the change to index.
   */
  public void index(ReviewDb db, Project.NameKey project, Change.Id changeId)
      throws IOException, OrmException {
    index(newChangeData(db, project, changeId), true);
  }
  /**
   * Synchronously index a change.
   * Do this without replication enabled, so it can be used locally from replication handlers and avoid recursive loops.
   *
   * @param db     review database.
   * @param change change to index.
   */
  public void indexNoRepl(ReviewDb db, Change change) throws IOException, OrmException {
    index(newChangeData(db, change), false);
  }

  /**
   * Synchronously index a change.
   *
   * @param db       review database.
   * @param project  the project to which the change belongs.
   * @param changeId ID of the change to index.
   */
  public void indexNoRepl(ReviewDb db, Project.NameKey project, Change.Id changeId)
      throws IOException, OrmException {
    index(newChangeData(db, project, changeId), false);
  }
  /**
   * Start deleting a change.
   *
   * @param id change to delete.
   * @return future for the deleting task.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<?, IOException> deleteAsync(Change.Id id) {
    return submit(new DeleteTask(id));
  }

  /**
   * Synchronously delete a change.
   *
   * @param id change ID to delete.
   */
  public void delete(Change.Id id) throws IOException {
    new DeleteTask(id).call();
  }

  /**
   * Asynchronously check if a change is stale, and reindex if it is.
   *
   * <p>Always run on the batch executor, even if this indexer instance is configured to use a
   * different executor.
   *
   * @param project the project to which the change belongs.
   * @param id      ID of the change to index.
   * @return future for reindexing the change; returns true if the change was stale.
   */
  @SuppressWarnings("deprecation")
  public com.google.common.util.concurrent.CheckedFuture<Boolean, IOException> reindexIfStale(
      Project.NameKey project, Change.Id id) {
    ReindexIfStaleTask task = new ReindexIfStaleTask(project, id);
    if (queuedReindexIfStaleTasks.add(task)) {
      return submit(task, batchExecutor);
    }
    return Futures.immediateCheckedFuture(false);
  }

  private void autoReindexIfStale(ChangeData cd) {
    autoReindexIfStale(cd.project(), cd.getId());
  }

  private void autoReindexIfStale(Project.NameKey project, Change.Id id) {
    if (autoReindexIfStale) {
      // Don't retry indefinitely; if this fails the change will be stale.
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError = reindexIfStale(project, id);
    }
  }

  private Collection<ChangeIndex> getWriteIndexes() {
    return indexes != null ? indexes.getWriteIndexes() : Collections.singleton(index);
  }

  @SuppressWarnings("deprecation")
  private <T> com.google.common.util.concurrent.CheckedFuture<T, IOException> submit(
      Callable<T> task) {
    return submit(task, executor);
  }

  @SuppressWarnings("deprecation")
  private static <T> com.google.common.util.concurrent.CheckedFuture<T, IOException> submit(
      Callable<T> task, ListeningExecutorService executor) {
    return Futures.makeChecked(
        Futures.nonCancellationPropagating(executor.submit(task)), IndexUtils.MAPPER);
  }

  private abstract class AbstractIndexTask<T> implements Callable<T> {
    protected final Project.NameKey project;
    protected final Change.Id id;
    protected final boolean replicationEnabled;

    protected AbstractIndexTask(Project.NameKey project, Change.Id id) {
      this.project = project;
      this.id = id;
      this.replicationEnabled = true;
    }

    protected AbstractIndexTask(Project.NameKey project, Change.Id id, boolean replicationEnabled) {
      this.project = project;
      this.id = id;
      this.replicationEnabled = replicationEnabled;
    }

    protected abstract T callImpl(Provider<ReviewDb> db) throws Exception;

    protected abstract void remove();

    @Override
    public abstract String toString();

    @Override
    public final T call() throws Exception {
      try {
        final AtomicReference<Provider<ReviewDb>> dbRef = Atomics.newReference();
        RequestContext newCtx =
            new RequestContext() {
              @Override
              public Provider<ReviewDb> getReviewDbProvider() {
                Provider<ReviewDb> db = dbRef.get();
                if (db == null) {
                  try {
                    db = Providers.of(schemaFactory.open());
                  } catch (OrmException e) {
                    throw new ProvisionException("error opening ReviewDb", e);
                  }
                  dbRef.set(db);
                }
                return db;
              }

              @Override
              public CurrentUser getUser() {
                throw new OutOfScopeException("No user during ChangeIndexer");
              }
            };
        RequestContext oldCtx = context.setContext(newCtx);
        try {
          return callImpl(newCtx.getReviewDbProvider());
        } finally {
          context.setContext(oldCtx);
          Provider<ReviewDb> db = dbRef.get();
          if (db != null) {
            db.get().close();
          }
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Failed to execute %s", this);
        throw e;
      }
    }
  }

  private class IndexTask extends AbstractIndexTask<Void> {
    private IndexTask(Project.NameKey project, Change.Id id) {
      super(project, id, true);
    }

    private IndexTask(Project.NameKey project, Change.Id id, boolean replicationEnabled) {
      super(project, id, replicationEnabled);
    }

    @Override
    public Void callImpl(Provider<ReviewDb> db) throws Exception {
      remove();
      ChangeData cd = newChangeData(db.get(), project, id);
      index(cd);
      return null;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(IndexTask.class, id.get());
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof IndexTask)) {
        return false;
      }
      IndexTask other = (IndexTask) obj;
      return id.get() == other.id.get();
    }

    @Override
    public String toString() {
      return "index-change-" + id;
    }

    @Override
    protected void remove() {
      queuedIndexTasks.remove(this);
    }
  }

  // Not AbstractIndexTask as it doesn't need ReviewDb.
  private class DeleteTask implements Callable<Void> {
    private final Change.Id id;

    private DeleteTask(Change.Id id) {
      this.id = id;
    }

    @Override
    public Void call() throws IOException {
      logger.atFine().log("Delete change %d from index.", id.get());
      // Don't bother setting a RequestContext to provide the DB.
      // Implementations should not need to access the DB in order to delete a
      // change ID.
      for (ChangeIndex i : getWriteIndexes()) {
        try (TraceTimer traceTimer =
            TraceContext.newTimer(
                "Deleting change %d in index version %d", id.get(), i.getSchema().getVersion())) {
          i.delete(id);
        }
      }
      fireChangeDeletedFromIndexEvent(id.get());
      return null;
    }
  }

  // TODO: (trevorg) GER-945 Raise this task and check it correctly only indexes on other nodes if it is stale.
  //  Now we may wish to consider...
  // Replicating this directly and letting each node decide if its own content is stale locally or not!!
  private class ReindexIfStaleTask extends AbstractIndexTask<Boolean> {
    private ReindexIfStaleTask(Project.NameKey project, Change.Id id) {
      super(project, id);
    }

    @Override
    public Boolean callImpl(Provider<ReviewDb> db) throws Exception {
      remove();
      try {
        if (stalenessChecker.isStale(id)) {
          logger.atFine().log("Change %s in project %s found to be stale reindexing replicated = %s .", id,
                              project.get(),
                              replicateAutoReindexIfStale);
          indexImpl(newChangeData(db.get(), project, id), replicateAutoReindexIfStale);
          return true;
        }
      } catch (NoSuchChangeException nsce) {
        logger.atFine().log("Change %s was deleted, aborting reindexing the change.", id.get());
      } catch (Exception e) {
        if (!isCausedByRepositoryNotFoundException(e)) {
          throw e;
        }
        logger.atFine().log(
            "Change %s belongs to deleted project %s, aborting reindexing the change.",
            id.get(), project.get());
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(ReindexIfStaleTask.class, id.get());
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ReindexIfStaleTask)) {
        return false;
      }
      ReindexIfStaleTask other = (ReindexIfStaleTask) obj;
      return id.get() == other.id.get();
    }

    @Override
    public String toString() {
      return "reindex-if-stale-change-" + id;
    }

    @Override
    protected void remove() {
      queuedReindexIfStaleTasks.remove(this);
    }
  }

  private boolean isCausedByRepositoryNotFoundException(Throwable throwable) {
    while (throwable != null) {
      if (throwable instanceof RepositoryNotFoundException) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }

  // Avoid auto-rebuilding when reindexing if reading is disabled. This just
  // increases contention on the meta ref from a background indexing thread
  // with little benefit. The next actual write to the entity may still incur a
  // less-contentious rebuild.
  private ChangeData newChangeData(ReviewDb db, Change change) throws OrmException {
    if (!notesMigration.readChanges()) {
      ChangeNotes notes = changeNotesFactory.createWithAutoRebuildingDisabled(change, null);
      return changeDataFactory.create(db, notes);
    }
    return changeDataFactory.create(db, change);
  }

  private ChangeData newChangeData(ReviewDb db, Project.NameKey project, Change.Id changeId)
      throws OrmException {
    if (!notesMigration.readChanges()) {
      ChangeNotes notes =
          changeNotesFactory.createWithAutoRebuildingDisabled(db, project, changeId);
      return changeDataFactory.create(db, notes);
    }
    return changeDataFactory.create(db, project, changeId);
  }
}
