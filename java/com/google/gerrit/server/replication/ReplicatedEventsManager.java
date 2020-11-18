
/**
 * Copyright (c) 2014-2020 WANdisco
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Apache License, Version 2.0
 * <p>
 */

/**
 * Replicated Events Manager to be used with WANdisco GitMS
 * Generated ChangeEvent(s) can be shared with other Gerrit Nodes
 *
 * http://www.wandisco.com/
 */
package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.events.EventBroker;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.util.Providers;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import org.eclipse.jgit.lib.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.google.gerrit.server.replication.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_EVENT_TYPES_TO_BE_SKIPPED;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE;
import static com.google.gerrit.server.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL;

/**
 * ReplicatedEventsManager is reponsible for the replication of events via GitMS Content Delivery.
 * It does this by a ReplicationEvents thread which listens on an event queue
 * and creates outgoing events from this information.
 * It also reads incoming events from files on disk, processes the information back into gerrit events
 * and plays them out locally to affect local cache state etc.
 */
@Singleton
public final class ReplicatedEventsManager implements LifecycleListener {

  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      // If replication is disabled, do not bring these classes.
      if ( Replicator.isReplicationDisabled() ) {
        logger.atInfo().log("Not binding these classes as replication is disabled.");
        return;
      }

      bind(ReplicatedEventsManager.class);
      listener().to(ReplicatedEventsManager.class);
    }
  }

  @Override
  public void start() {
    logger.atInfo().log("Create the rep event listener now!");

    worker = new ReplicatedEventsWorker(this, changeHookRunner);
    worker.startReplicationThread();
  }

  @Override
  public void stop() {
    logger.atInfo().log("Stop the rep event listener now!");
    worker.stopReplicationThread();
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir");


  // Configuration in replication events manager which governs this and its worker thread.
  private File internalLogFile = null; // used for debug
  private List<String> eventSkipList = new ArrayList<>();
  public  boolean internalLogEnabled = false;
  private boolean replicatedEventsReceive = true; // receive and original are synonym
  private boolean replicatedEventsReplicateOriginalEvents = true; // receive and original are synonym
  private boolean receiveReplicatedEventsEnabled = true;
  private boolean replicatedEventsEnabled = true;
  private boolean replicatedEventsSend = true;
  private long maxSecsToWaitForEventOnQueue;
  private ReplicatedEventsWorker worker;


  public File getInternalLogFile() {
    return internalLogFile;
  }

  public List<String> getEventSkipList() {
    return eventSkipList;
  }

  public boolean isInternalLogEnabled() {
    return internalLogEnabled;
  }

  public boolean isReplicatedEventsReceive() {
    return replicatedEventsReceive;
  }

  public boolean isReplicatedEventsReplicateOriginalEvents() {
    return replicatedEventsReplicateOriginalEvents;
  }

  public boolean isReceiveReplicatedEventsEnabled() {
    return receiveReplicatedEventsEnabled;
  }

  public boolean isReplicatedEventsEnabled() {
    return replicatedEventsEnabled;
  }

  public boolean isReplicatedEventsSend() {
    return replicatedEventsSend;
  }

  public long getMaxSecsToWaitForEventOnQueue() {
    return maxSecsToWaitForEventOnQueue;
  }

  private EventBroker changeHookRunner;
  // Use schemaFactory directly as we can't use Request Scoped Providers
  private final SchemaFactory<ReviewDb> schemaFactory;

  // use changeNotesFactory to abstract away the loading of changes from reviewDB or notesDB.
  private final ChangeNotes.Factory changeNotesFactory;

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

  public ChangeNotes.Factory getChangeNotesFactory() {
    return changeNotesFactory;
  }

  @Inject
  public ReplicatedEventsManager(
      EventBroker changeHookRunner,
      SchemaFactory<ReviewDb> schemaFactory,
      ChangeNotes.Factory changeNotesFactory,
      @GerritServerConfig Config config
  ) {
    this.changeHookRunner = changeHookRunner;
    this.schemaFactory = schemaFactory;
    this.changeNotesFactory = changeNotesFactory;

    Replicator.setGerritConfig(config == null ? new Config() : config);

    logger.atInfo().log("RE ReplicatedEvents instance added");

    boolean configOk = readConfiguration();
    logger.atInfo().log("RE Configuration read: ok? %s, replicatedEvent are enabled? %s", configOk, replicatedEventsEnabled);
    logger.atInfo().log("RE ReplicatedEvents hook called...");

    if (internalLogEnabled) {
      internalLogFile = new File(new File(DEFAULT_BASE_DIR), "replEvents.log"); // used for debug
      System.err.println("LOG FILE: " + internalLogFile);
    }
  }

  /** hide default constructor. **/
  private ReplicatedEventsManager() {
    changeHookRunner = null;
    schemaFactory = null;
    throw new RuntimeException("Not Supported.");
  }

  private boolean readConfiguration() {
    GitMsApplicationProperties props = Replicator.getApplicationProperties();

    replicatedEventsSend = true; // they must be always enabled, not dependant on GERRIT_REPLICATED_EVENTS_ENABLED_SEND
    replicatedEventsReceive = props.getPropertyAsBoolean(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE, "true");
    replicatedEventsReplicateOriginalEvents = props.getPropertyAsBoolean(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL, "true");

    receiveReplicatedEventsEnabled = replicatedEventsReceive || replicatedEventsReplicateOriginalEvents;
    replicatedEventsEnabled = receiveReplicatedEventsEnabled || replicatedEventsSend;
    if (replicatedEventsEnabled) {
      maxSecsToWaitForEventOnQueue = Long.parseLong(Replicator.cleanLforLongAndConvertToMilliseconds(props.getProperty(
          GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE, DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE)));
      logger.atInfo().log("RE Replicated events are enabled, send: %s, receive: %s", replicatedEventsSend, receiveReplicatedEventsEnabled);
    } else {
      logger.atInfo().log("RE Replicated events are disabled"); // This could not apppear in the log... cause the log could not yet be ready
    }

    //Read in a comma separated list of events that should be skipped.
    eventSkipList = props.getPropertyAsList(GERRIT_EVENT_TYPES_TO_BE_SKIPPED);
    //Setting all to lowercase so user doesn't have to worry about correct casing.
    eventSkipList.replaceAll(String::toLowerCase);

    logger.atInfo().log("RE Replicated events: receive=%s, original=%s, send=%s ",
        replicatedEventsReceive, replicatedEventsReplicateOriginalEvents, replicatedEventsSend);

    return true;
  }

}
