
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


import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.util.Providers;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PackParser;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is to manage the replication of the change events happening in
 * Gerrit, from within one Gerrit to the other replicated Gerrit.
 * This is meant as a replacement for the Gerrit Plug-In we have been using until
 * Gerrit version 2.10.6, which was catching the change events from the outside and
 * then sending the proposals to GitMS.
 * When an ASYNC (or SYNC) change index event is called by Gerrit it is registered with this class and then
 * it is replicated on the other nodes, where a REPL-SYNC change event is produced.
 * If it's not possible to index the change immediately because the data on the DB is not
 * up-to-date then the change index data is saved in a file for a future retry attempt.
 * <p>
 * A thread will constantly look for files of the retry kind and retry the index of the changes.
 * <p>
 * For some reason there can be some differences on the replicated gerrits if we keep indexing
 * the changes after the original ones on the original node. This can be overcome by indexing again
 * that original change on the original node after a while. This is accomplished using the localReindexQueue
 * which will keep record of the changes indexed on the local node and index them again after a while.
 * <p>
 * See also ChangeIndexer#indexAsync()
 *
 * @author antonio
 */
@Singleton
public class ReplicatedIndexEventManager implements LifecycleListener {


  /**
   * Module is used to setup the listener, and bind this class to be used on startup for anyone that calls this module.
   * It also hooks in the LifeCycleListener so that we get start/stop calls on the application context.
   * E.g. we call this in the Daemon setup code.
   */
  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      bind(ReplicatedIndexEventManager.class);
      listener().to(ReplicatedIndexEventManager.class);
    }
  }

  /**
   * Listen to lifecycle start.
   * Start the actual worker, and whatever threads it needs to kick of to do teh IndexEvent work.
   */
  @Override
  public void start() {
    logger.atInfo().log("Create the rep event listener now!");

    if ( worker != null ){
      // we should never overwrite our worker part way through - throw as this is invalid!!
      throw new RuntimeException("Invalid state - lifecycle start called on already running ReplicatedIndexEventManager");
    }

    worker = new ReplicatedIndexEventsWorker(this, indexer);
    worker.start();
  }

  /**
   * Listen to lifecycle stop.
   * Stop the worker and whatever threads it is responsible for.
   */
  @Override
  public void stop() {
    logger.atInfo().log("Stop the rep event listener now!");
    worker.stop();
    worker = null;
    instance = null;
  }

  private static ReplicatedIndexEventsWorker worker;
  private SchemaFactory<ReviewDb> schemaFactory;
  private ChangeIndexer indexer;

  // HACK! This is only here until the caller uses dependency injection.. At which point it can simply inject this singleton
  // instead of requiring us to hold onto a static instance to ourself....
  private static ReplicatedIndexEventManager instance;

  // configuration
  private static final String INCOMING_PERSISTED_LINGER_TIME_KEY = "ReplicatedIndexEventManagerIncomingPersistedLingerTime";
  private static final long INCOMING_PERSISTED_LINGER_TIME_DEFAULT = 259200000L; // 3 days

  private long INCOMING_PERSISTED_LINGER_TIME_VALUE = 0L;

  public long getIncomingPersistedLingerTime() {
    return INCOMING_PERSISTED_LINGER_TIME_VALUE;
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Please note as this returns a Provider of a ReviewDB.  As such the instance of the DB isn't really open until
   * the provider.get() is used.  Allowing tidy try( ReviewDb db = provider.get() ) blocks to be used.
   *
   * @return Provider<ReviewDB> instance
   * @throws OrmException
   */
  public Provider<ReviewDb> getReviewDbProvider() throws OrmException {
    return Providers.of(schemaFactory.open());
  }

  @Inject
  public ReplicatedIndexEventManager(
      SchemaFactory<ReviewDb> schemaFactory,
      ChangeIndexer indexer,
      @GerritServerConfig Config config
  ) {
    this.schemaFactory = schemaFactory;
    this.indexer = indexer;

    // setup any configuration, saves us messing around later
    if (INCOMING_PERSISTED_LINGER_TIME_VALUE == 0) {
      if (config != null) {
        INCOMING_PERSISTED_LINGER_TIME_VALUE = config.getLong("wandisco", null,
            INCOMING_PERSISTED_LINGER_TIME_KEY, INCOMING_PERSISTED_LINGER_TIME_DEFAULT);
      } else {
        INCOMING_PERSISTED_LINGER_TIME_VALUE = INCOMING_PERSISTED_LINGER_TIME_DEFAULT;
      }
    }
  }

  /**
   * Main method used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes.
   * <p>
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  public static void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
    queueReplicationIndexEvent(indexNumber, projectName, lastUpdatedOn, false);
  }

  /**
   * Queue a notification to be made to replica nodes regarding the deletion of an index. This must be done
   * independently of the delete() call in ChangeIndexer, as in that context, the Change is no longer
   * accessible, preventing lookup of the project name and the subsequent attempt to tie the change to
   * a specific DSM in the replicator.
   *
   * @param indexNumber
   * @param projectName
   */
  public static void queueReplicationIndexDeletionEvent(int indexNumber, String projectName) {
    queueReplicationIndexEvent(indexNumber, projectName, new Timestamp(System.currentTimeMillis()), true);
  }

  /**
   * Used by the gerrit ChangeIndexer to communicate that a new index event has happened
   * and must be replicated across the nodes with an additional boolean flag to indicate if the index
   * to be updated is being deleted.
   * <p>
   * This will enqueue the the event for async replication
   *
   * @param indexNumber
   * @param projectName
   * @param lastUpdatedOn
   */
  private static void queueReplicationIndexEvent(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean deleteIndex) {

    if ( worker == null )
    {
      // someone is trying to queue a replication event - when we have no replication worker.
      // This happens when we dont register the ReplicatedIndexEventsManager which we only do for main Daemon currently!!
      return;
    }
    
    worker.addIndexEventToUnfilteredReplicationQueue(indexNumber, projectName, lastUpdatedOn, deleteIndex);
  }


  public static ReplicatedIndexEventManager getInstance() {
    return instance;
  }

  /**
   * Replicate the changes that are to be deleted when we preserve a replicated repo
   * Takes a list of int's
   *
   * @throws IOException
   */
  public void deleteChanges(int[] changes) throws IOException {
    //iterate over the list of changes and delete each one
    for (int i : changes) {
      indexer.delete(new Change.Id(i));
      logger.atFiner().log("Deleted change %d", i);
    }
  }

  /**
   * Replicate the changes that are to be deleted when we preserve a replicated repo
   * Takes a list of Change.ID objects.
   *
   * @throws IOException
   */
  public void deleteChanges(List<Change.Id> changes) throws IOException {
    //iterate over the list of changes and delete each one
    for (Change.Id id : changes) {
      indexer.delete(id);
      logger.atFiner().log("Deleted change %s", id.toString());
    }
  }

}
