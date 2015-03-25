package com.google.gerrit.server.events;

import com.google.gerrit.common.ReplicatedEventsManager;
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
public class ChangeEventWrapper  {
  public final String changeEvent;
  public final String className;
  public final String projectName;
  private static final Gson gson = new Gson();
  
  public ChangeEventWrapper(ChangeEvent changeEvent) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = null;
  }

  public ChangeEventWrapper(ChangeEvent changeEvent, ReplicatedEventsManager.ChangeEventInfo info) {
    this.changeEvent = gson.toJson(changeEvent);
    this.className=changeEvent.getClass().getName();
    this.projectName = info.getProjectName();
  }
}
