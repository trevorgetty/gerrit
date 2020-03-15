package com.google.gerrit.common;

import com.google.common.base.Supplier;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT;

/**
 * @author ronanconway
 * 2.13.12 version of GerritEventFactory. Note that this version is slighly different
 * to the version now available in 2.16 gerrit.
 */
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


  public static EventWrapper createReplicatedChangeEvent( Event changeEvent, ReplicatedEventsManager.ChangeEventInfo info, String prefix ){
    String eventString = gson.toJson(changeEvent);
    return new EventWrapper ( eventString, changeEvent.getClass().getName(), info.getProjectName(), EventWrapper.Originator.GERRIT_EVENT, prefix );
  }


  //This type of cache eventWrapper is for the All-Projects as projectName is null.
  public static EventWrapper createReplicatedAllProjectsCacheEvent( CacheKeyWrapper cacheNameAndKey ){
    String eventString = gson.toJson(cacheNameAndKey);
    return new EventWrapper ( eventString, cacheNameAndKey.getClass().getName(), cacheNameAndKey.key.toString(), EventWrapper.Originator.CACHE_EVENT );
  }

  public static EventWrapper createReplicatedCacheEvent( String projectName, CacheKeyWrapper cacheNameAndKey ){
    String eventString = gson.toJson(cacheNameAndKey);
    return new EventWrapper ( eventString, cacheNameAndKey.getClass().getName(), projectName, EventWrapper.Originator.CACHE_EVENT );
  }


  public static EventWrapper createReplicatedIndexEvent( ReplicatedIndexEventManager.IndexToReplicate indexToReplicate ){
    String eventString = gson.toJson(indexToReplicate);
    return new EventWrapper ( eventString, indexToReplicate.getClass().getName(), indexToReplicate.projectName, EventWrapper.Originator.INDEX_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectChangeEvent( DeleteProjectChangeEvent deleteProjectChangeEvent ){
    String eventString = gson.toJson(deleteProjectChangeEvent);
    return new EventWrapper ( eventString, deleteProjectChangeEvent.getClass().getName(), deleteProjectChangeEvent.project.getName(), EventWrapper.Originator.DELETE_PROJECT_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectEvent( ProjectInfoWrapper projectInfoWrapper ){
    String eventString = gson.toJson(projectInfoWrapper);
    return new EventWrapper ( eventString, projectInfoWrapper.getClass().getName(), projectInfoWrapper.projectName, EventWrapper.Originator.DELETE_PROJECT_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectMessageEvent(DeleteProjectMessageEvent deleteProjectMessageEvent ){
    String eventString = gson.toJson(deleteProjectMessageEvent);
    return new EventWrapper ( eventString, deleteProjectMessageEvent.getClass().getName(), deleteProjectMessageEvent.getProject(), DELETE_PROJECT_MESSAGE_EVENT);
  }


  public static EventWrapper createReplicatedAccountIndexEvent( String projectName, AccountIndexEvent accountIndexEvent, EventWrapper.Originator originator ){
    String eventString = gson.toJson(accountIndexEvent);
    return new EventWrapper ( eventString, accountIndexEvent.getClass().getName(), projectName, originator);
  }

}
