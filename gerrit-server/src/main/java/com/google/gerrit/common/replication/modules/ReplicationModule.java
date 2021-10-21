package com.google.gerrit.common.replication.modules;

import com.google.common.base.Supplier;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.GerritEventDataPropertiesDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.LongSerializationPolicy;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import java.util.Map;


public class ReplicationModule extends LifecycleModule {

  @Override
  protected void configure() {
    /* Sets up the bindings for the ReplicatedEventsCoordinator*/
    install(new ReplicatedCoordinatorModule());
    /* The ReplicatedOutgoingEventsFeed differs to the other feeds in that it sets up an EventListener
     * and registers that listener with the EventBroker. It also does not need a member variable inside the
     * ReplicatedEventsCoordinator as no other class needs to access it via a getter. For this reason it is
     * distinct from the other feeds and does not need to be instantiated by the ReplicatedEventsCoordinator.*/
    install(new ReplicatedOutgoingServerEventsFeed.Module());
    /* GerritEventFactory is a utility class full of static methods. It needs static injection for the
       provided Gson instance*/
    install(new GerritEventFactory.Module());
  }

  @Provides
  @Named("wdGson")
  @Singleton
  public Gson provideGson(){
    // Using setLongSerializationPolicy to prevent longs appearing as scientific notation after deserialization.
    // Configures Gson to apply a specific serialization policy for Long and long objects.
    return new GsonBuilder().setLongSerializationPolicy(LongSerializationPolicy.STRING)
        // An EventWrapper is composed of several members one of which is a GerritEventData instance.
        // This instance has a property map as a member, i.e Map<String, Object> properties.
        // We need to register a type adapter here to tell it how to deserialize the properties map correctly otherwise
        // it will use the gson default of double for numbers when deserializing number values in the map.
        .registerTypeAdapter(new TypeToken<Map<String, Object>>(){}.getType(), new GerritEventDataPropertiesDeserializer())
        .registerTypeAdapter(Supplier.class, new SupplierSerializer())
        .registerTypeAdapter(Event.class, new EventDeserializer())
        .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
        .create();
  }
}
