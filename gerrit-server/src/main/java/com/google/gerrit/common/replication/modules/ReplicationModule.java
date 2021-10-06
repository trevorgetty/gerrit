package com.google.gerrit.common.replication.modules;

import com.google.common.base.Supplier;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;


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
    return new GsonBuilder()
        .registerTypeAdapter(Supplier.class, new SupplierSerializer())
        .registerTypeAdapter(Event.class, new EventDeserializer())
        .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
        .create();
  }
}
