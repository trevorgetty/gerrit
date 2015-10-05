package com.google.gerrit.server.events;

import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.ReplicatedEventsManager;
import com.google.gerrit.common.ReplicatedIndexEventManager;
import com.google.gson.Gson;


/**
 * This class is used to exchange events with the other nodes in the Gerrit replication with WANdisco GitMS
 * This class has some public fields WHICH MUST BE TAKEN UP TO DATE with the ones in the same-name class
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
    INDEX_EVENT
  }
  public final String event;
  public final String className;
  public final String projectName;
  public final Originator originator;
  public final String prefix;
  private static final Gson gson = new Gson();

  public EventWrapper(Event changeEvent) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public EventWrapper(Event changeEvent, String prefix) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = prefix;
  }

  public EventWrapper(Event changeEvent, ReplicatedEventsManager.ChangeEventInfo info) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public EventWrapper(Event changeEvent, ReplicatedEventsManager.ChangeEventInfo info, String prefix) {
    this.event = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = prefix;
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
}
