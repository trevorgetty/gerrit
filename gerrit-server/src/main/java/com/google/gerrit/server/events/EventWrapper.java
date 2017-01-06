package com.google.gerrit.server.events;

import com.google.common.base.Supplier;
import com.google.gerrit.common.CacheKeyWrapper;
import com.google.gerrit.common.DeleteProjectChangeEvent;
import com.google.gerrit.common.ProjectInfoWrapper;
import com.google.gerrit.common.ReplicatedEventsManager;
import com.google.gerrit.common.ReplicatedIndexEventManager;
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
    FOR_REPLICATOR_EVENT
  }
  public final String event;
  public final String className;
  public final String projectName;
  public final Originator originator;
  public final String prefix;
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      //.registerTypeAdapter(Project.NameKey.class, new ProjectNameKeySerializer())
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

  @Override
  public String toString() {
    return "EventWrapper{" + "event=" + event + ", className=" + className + ", projectName=" + projectName + ", originator=" + originator + ", prefix=" + prefix + '}';
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

  public EventWrapper(String projectName, Object object, Originator orig, String className, String prefix) {
    this.event = object == null? null : gson.toJson(object);
    this.className = className;
    this.projectName = projectName;
    this.originator = orig;
    this.prefix = prefix;
  }
}
