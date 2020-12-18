package com.google.gerrit.server.replication;

import com.google.common.base.Supplier;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.CACHE_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.GERRIT_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.PROJECTS_INDEX_EVENT;

public class GerritEventFactory {

  // Gson performs the serialization/deserialization of objects using its inbuilt adapters.
  // Java objects can be serialised to JSON strings and deserialised back using JsonSerializer
  // and the JsonDeserializer respectively. SupplierSerializer/SupplierDeserializer and EventDeserializer
  // extend these JsonSerializer/JsonDeserializer
  private static final Gson gson = new GsonBuilder()
      .registerTypeAdapter(Supplier.class, new SupplierSerializer())
      .registerTypeAdapter(Event.class, new EventDeserializer())
      .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
      .create();

  public static EventWrapper createReplicatedChangeEvent(Event changeEvent,
                                                         ReplicatedChangeEventInfo info){
    String eventString = gson.toJson(changeEvent);
    return new EventWrapper(eventString,
                            changeEvent.getClass().getName(),
                            info.getProjectName(),
                            GERRIT_EVENT);
  }

  /**
   * This type of cache eventWrapper is for the All-Projects as projectName is null.
   *
   * NB: This is actually called for other projects too, however, as projectName comes from the key inside
   * CacheKeyWrapper it will generally call toString to unwrap, but we know that in Gerrit it's likely to
   * be a {@link Project.NameKey} instance which will require calling .get() instead to get the original
   * project name string entered by the user.
   *
   * @param cacheNameAndKey Wrapper around cache name to affect.
   * @return Outgoing cache event.
   */
  public static EventWrapper createReplicatedAllProjectsCacheEvent(CacheKeyWrapper cacheNameAndKey){
    final String eventString = gson.toJson(cacheNameAndKey);
    final Object key = cacheNameAndKey.key;
    final String projectName = (key instanceof Project.NameKey) ? ((Project.NameKey)key).get() : key.toString();

    return new EventWrapper(eventString,
                            cacheNameAndKey.getClass().getName(),
                            projectName,
                            CACHE_EVENT);
  }

  public static EventWrapper createReplicatedProjectsIndexEvent(String projectName,
                                                                ProjectIndexEvent indexEvent){
    final String eventString = gson.toJson(indexEvent);
    return new EventWrapper(eventString,
                            indexEvent.getClass().getName(),
                            projectName,
                            PROJECTS_INDEX_EVENT);
  }

  public static EventWrapper createReplicatedCacheEvent(String projectName, CacheKeyWrapper cacheNameAndKey){
    String eventString = gson.toJson(cacheNameAndKey);
    return new EventWrapper(eventString,
                            cacheNameAndKey.getClass().getName(),
                            projectName,
                            CACHE_EVENT);
  }

  public static EventWrapper createReplicatedIndexEvent(ReplicatedIndexEventsWorker.IndexToReplicate indexToReplicate){
    String eventString = gson.toJson(indexToReplicate);
    return new EventWrapper(eventString,
                            indexToReplicate.getClass().getName(),
                            indexToReplicate.projectName,
                            INDEX_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectChangeEvent(DeleteProjectChangeEvent deleteProjectChangeEvent){
    String eventString = gson.toJson(deleteProjectChangeEvent);
    return new EventWrapper(eventString,
                            deleteProjectChangeEvent.getClass().getName(),
                            deleteProjectChangeEvent.project.getName(),
                            DELETE_PROJECT_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectEvent(ProjectInfoWrapper projectInfoWrapper){
    String eventString = gson.toJson(projectInfoWrapper);
    return new EventWrapper(eventString,
                            projectInfoWrapper.getClass().getName(),
                            projectInfoWrapper.projectName,
                            DELETE_PROJECT_EVENT);
  }

  public static EventWrapper createReplicatedDeleteProjectMessageEvent(DeleteProjectMessageEvent deleteProjectMessageEvent ){
    String eventString = gson.toJson(deleteProjectMessageEvent);
    return new EventWrapper(eventString,
                            deleteProjectMessageEvent.getClass().getName(),
                            deleteProjectMessageEvent.getProject(),
                            DELETE_PROJECT_MESSAGE_EVENT);
  }

  //Will create an EventWrapper for either an ACCOUNT_USER_INDEX_EVENT or a ACCOUNT_GROUP_INDEX_EVENT
  public static EventWrapper createReplicatedAccountIndexEvent(String projectName,
                                                               AccountIndexEventBase accountIndexEventBase,
                                                               EventWrapper.Originator originator){
    String eventString = gson.toJson(accountIndexEventBase);
    return new EventWrapper(eventString,
                            accountIndexEventBase.getClass().getName(),
                            projectName,
                            originator);
  }

}
