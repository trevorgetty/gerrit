
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

package com.google.gerrit.server.events;

import com.google.common.base.Supplier;
import com.google.gerrit.server.replication.AccountIndexEventBase;
import com.google.gerrit.server.replication.CacheKeyWrapper;
import com.google.gerrit.server.replication.DeleteProjectChangeEvent;
import com.google.gerrit.server.replication.ProjectInfoWrapper;
import com.google.gerrit.server.replication.ReplicatedEventsManager;
import com.google.gerrit.server.replication.ReplicatedIndexEventManager;
import com.google.gerrit.server.replication.ReplicatorMessageEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * This class is used to exchange events with the other nodes in the Gerrit replication with WANdisco GitMS
 * This class has some public fields WHICH MUST BE KEPT UP TO DATE with the ones in the same-name class
 * in the GitMS, used to rebuild the object on the GitMS side
 *
 * http://www.wandisco.com/
 *
 * @author antoniochirizzi
 */
public class EventWrapper  {
  /*****
   * Are you changing this class? Then you need to change also EventWrapper in com.wandisco.gitms.repository.handlers.gerrit.GerritReplicatedEventStreamProposer
   */
  public static enum Originator {
    GERRIT_EVENT,
    CACHE_EVENT,
    INDEX_EVENT,
    PACKFILE_EVENT,
    DELETE_PROJECT_EVENT,
    FOR_REPLICATOR_EVENT,
    ACCOUNT_USER_INDEX_EVENT,
    ACCOUNT_GROUP_INDEX_EVENT
  }
  public final String event;
  public final String className;
  public final String projectName;
  public final Originator originator;
  /**
   * No longer in use as all events are now replicated events by default.
   */
  @Deprecated()
  public final String prefix;

  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();

  public EventWrapper(Event changeEvent) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public EventWrapper(Event changeEvent, ReplicatedEventsManager.ChangeEventInfo info) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public EventWrapper(String projectName, CacheKeyWrapper cacheNameAndKey) {
    this.event = gson.toJson(cacheNameAndKey);
    this.className=cacheNameAndKey.getClass().getName();
    this.projectName = projectName;
    this.originator = Originator.CACHE_EVENT;
    this.prefix = null;
  }

  public EventWrapper(CacheKeyWrapper cacheNameAndKey) {
    this.event = gson.toJson(cacheNameAndKey);
    this.className=cacheNameAndKey.getClass().getName();
    this.projectName = null;
    this.originator = Originator.CACHE_EVENT;
    this.prefix = null;
  }

  public EventWrapper(ReplicatedIndexEventManager.IndexToReplicate indexToReplicate) {
    this.event = gson.toJson(indexToReplicate);
    this.className=indexToReplicate.getClass().getName();
    this.projectName = indexToReplicate.projectName;
    this.originator = Originator.INDEX_EVENT;
    this.prefix = null;
  }

  @Override
  public String toString() {
    return String.format("EventWrapper{ event=%s, className=%s, projectName=%s, originator=%s }", event, className, projectName, originator );
  }

  public EventWrapper(ProjectInfoWrapper projectInfoWrapper) {
    this.event = gson.toJson(projectInfoWrapper);
    this.className=projectInfoWrapper.getClass().getName();
    this.projectName = projectInfoWrapper.projectName;
    this.originator = Originator.DELETE_PROJECT_EVENT;
    this.prefix = null;
  }

  public EventWrapper(DeleteProjectChangeEvent deleteProjectChangeEvent) {
    this.event = gson.toJson(deleteProjectChangeEvent);
    this.className=deleteProjectChangeEvent.getClass().getName();
    this.projectName = deleteProjectChangeEvent.project.getName();
    this.originator = Originator.DELETE_PROJECT_EVENT;
    this.prefix = null;
  }


  public EventWrapper(String projectName, AccountIndexEventBase accountIndexEventBase, Originator originator) {
    this.event = gson.toJson(accountIndexEventBase);
    this.className= accountIndexEventBase.getClass().getName();
    this.projectName = projectName;
    this.originator = originator;
    this.prefix = null;
  }

  /**
   * This EventWrapper constructor can be used for sending status messages
   * to the GitMS replicator. The event is generic enough so that custom messages
   * can be passed to the GitMS replicator.
   * @param replicatorMessageEvent
   */
  public EventWrapper(ReplicatorMessageEvent replicatorMessageEvent) {
    this.event = gson.toJson(replicatorMessageEvent);
    this.className=replicatorMessageEvent.getClass().getName();
    this.projectName = replicatorMessageEvent.project;
    this.originator = Originator.FOR_REPLICATOR_EVENT;
    this.prefix = replicatorMessageEvent.prefix;
  }

}
