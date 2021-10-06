package com.google.gerrit.common.replication.modules;

import com.google.gerrit.common.replication.coordinators.DummyReplicatedEventsCoordinatorImpl;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Scopes;

public class DummyReplicationModule extends LifecycleModule {
  @Override
  protected void configure() {
    DynamicItem.itemOf(binder(), ReplicatedEventsCoordinator.class);
    DynamicItem.bind(binder(), ReplicatedEventsCoordinator.class)
        .to(DummyReplicatedEventsCoordinatorImpl.class).in(Scopes.SINGLETON);
    //Do not register any other replication classes here.
  }
}
