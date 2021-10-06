package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

public abstract class GerritPublishableImpl implements GerritPublishable {

  protected final EventWrapper.Originator eventType;
  protected final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  protected final Gson gson;

  // indicate what type of event we are interested in here!
  protected GerritPublishableImpl(EventWrapper.Originator eventType, ReplicatedEventsCoordinator replicatedEventsCoordinator){
    this.eventType = eventType;
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.gson = replicatedEventsCoordinator.getGson();
  }

  @Override
  public void subscribeEvent(GerritPublishable toCall){
    replicatedEventsCoordinator.subscribeEvent(eventType, toCall);
  }

  @Override
  public void unsubscribeEvent(GerritPublishable toCall) {
    // lets unsubscribe our listener now we are stopping.
    replicatedEventsCoordinator.unsubscribeEvent(eventType, toCall);
  }

}
