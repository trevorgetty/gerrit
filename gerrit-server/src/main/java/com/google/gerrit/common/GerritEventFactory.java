package com.google.gerrit.common;

import com.google.gerrit.common.replication.IndexToReplicate;
import com.google.gerrit.common.replication.ReplicatedChangeEventInfo;
import com.google.gerrit.common.replication.modules.ReplicationModule;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.Event;
import com.google.gson.Gson;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.wandisco.gerrit.gitms.shared.events.DeleteProjectMessageEvent;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT;

/**
 * @author ronanconway
 * 2.13.12 version of GerritEventFactory. Note that this version is slighly different
 * to the version now available in 2.16 gerrit.
 */
public class GerritEventFactory {

  /**
   * GerritEventFactory is a utility class made up of static methods. In order to dependency inject these
   * we must use requestStaticInjection().
   * @see <a href="https://google.github.io/guice/api-docs/4.2/javadoc/index.html?com/google/inject/spi/StaticInjectionRequest.html">
   *   google.github.io</a>
   */
  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      requestStaticInjection(GerritEventFactory.class);
    }
  }

  /**
   * Used only by integration / unit testing to setup the Injected fields that wont be initialized e.g. GSon.
   */
  public static void setupEventWrapper(){
    if ( gson == null ) {
      gson = new ReplicationModule().provideGson();
    }
  }

  // Gson performs the serialization/deserialization of objects using its inbuilt adapters.
  // Java objects can be serialised to JSON strings and deserialized back using JsonSerializer
  // and the JsonDeserializer respectively. SupplierSerializer/SupplierDeserializer and EventDeserializer
  // extend these JsonSerializer/JsonDeserializer
  @Inject
  @Named("wdGson")
  private static Gson gson;

  public static EventWrapper createReplicatedChangeEvent(Event changeEvent, ReplicatedChangeEventInfo info) throws IOException {
    String eventString = gson.toJson(changeEvent);
    return new EventWrapper ( eventString, changeEvent.getClass().getName(), info.getProjectName(), EventWrapper.Originator.GERRIT_EVENT);  }

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
  public static EventWrapper createReplicatedAllProjectsCacheEvent(CacheKeyWrapper cacheNameAndKey ) throws IOException {

    final String eventString = gson.toJson(cacheNameAndKey);
    final Object key = cacheNameAndKey.key;
    final String projectName = (key instanceof Project.NameKey) ? ((Project.NameKey)key).get() : key.toString();

    return new EventWrapper ( eventString, cacheNameAndKey.getClass().getName(), projectName, EventWrapper.Originator.CACHE_EVENT );
  }

  public static EventWrapper createReplicatedCacheEvent( String projectName, CacheKeyWrapper cacheNameAndKey ) throws IOException {
    String eventString = gson.toJson(cacheNameAndKey);
    return new EventWrapper ( eventString, cacheNameAndKey.getClass().getName(), projectName, EventWrapper.Originator.CACHE_EVENT );
  }


  public static EventWrapper createReplicatedIndexEvent( IndexToReplicate indexToReplicate ) throws IOException {
    String eventString = gson.toJson(indexToReplicate);
    return new EventWrapper ( eventString, indexToReplicate.getClass().getName(), indexToReplicate.projectName, EventWrapper.Originator.INDEX_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectChangeEvent( DeleteProjectChangeEvent deleteProjectChangeEvent ) throws IOException {
    String eventString = gson.toJson(deleteProjectChangeEvent);
    return new EventWrapper ( eventString, deleteProjectChangeEvent.getClass().getName(), deleteProjectChangeEvent.project.getName(), EventWrapper.Originator.DELETE_PROJECT_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectEvent( ProjectInfoWrapper projectInfoWrapper ) throws IOException {
    String eventString = gson.toJson(projectInfoWrapper);
    return new EventWrapper ( eventString, projectInfoWrapper.getClass().getName(), projectInfoWrapper.projectName, EventWrapper.Originator.DELETE_PROJECT_EVENT );
  }


  public static EventWrapper createReplicatedDeleteProjectMessageEvent(DeleteProjectMessageEvent deleteProjectMessageEvent ) throws IOException {
    String eventString = gson.toJson(deleteProjectMessageEvent);
    return new EventWrapper ( eventString, deleteProjectMessageEvent.getClass().getName(), deleteProjectMessageEvent.getProject(), DELETE_PROJECT_MESSAGE_EVENT);
  }


  public static EventWrapper createReplicatedAccountIndexEvent( String projectName, AccountIndexEvent accountIndexEvent, EventWrapper.Originator originator ) throws IOException {
    String eventString = gson.toJson(accountIndexEvent);
    return new EventWrapper ( eventString, accountIndexEvent.getClass().getName(), projectName, originator);
  }

}
