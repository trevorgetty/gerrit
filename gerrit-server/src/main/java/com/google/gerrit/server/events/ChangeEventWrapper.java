package com.google.gerrit.server.events;

import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.ReplicatedEventsManager;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to exchange events with the other nodes in the Gerrit replication with WANdisco GitMS
 * This class has some public fields WHICH MUST BE TAKEN UP TO DATE with the ones in the same-name class
 * in the GitMS, used to rebuild the object on the GitMS side
 *
 * http://www.wandisco.com/
 *
 * @author antoniochirizzi
 */
public class ChangeEventWrapper  {
  public static enum Originator {
    GERRIT_EVENT,
    CACHE_EVENT
  }
  public final String changeEvent;
  public final String className;
  public final String projectName;
  public final Originator originator;
  public final String prefix;
  private static final Gson gson = new Gson();

  public ChangeEventWrapper(ChangeEvent changeEvent) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public ChangeEventWrapper(ChangeEvent changeEvent, String prefix) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = prefix;
  }

  public ChangeEventWrapper(ChangeEvent changeEvent, ReplicatedEventsManager.ChangeEventInfo info) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = null;
  }

  public ChangeEventWrapper(ChangeEvent changeEvent, ReplicatedEventsManager.ChangeEventInfo info, String prefix) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();;
    this.originator = Originator.GERRIT_EVENT;
    this.prefix = prefix;
  }

    public ChangeEventWrapper(CacheKeyWrapper cacheNameAndKey) {
    this.changeEvent = gson.toJson(cacheNameAndKey);
    this.className=cacheNameAndKey.getClass().getName();
    this.projectName = null;
    this.originator = Originator.CACHE_EVENT;
    this.prefix = null;
  }
}
