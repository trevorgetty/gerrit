package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;

/**
 * I don't want to confuse this with a real feed class, but this is going to be the base class that allows
 * all feeds to use the same type of base information from the events coordinator.
 */
public class ReplicatedOutgoingEventsFeedCommon {
  protected final ReplicatedEventsCoordinator replicatedEventsCoordinator;

  protected ReplicatedOutgoingEventsFeedCommon(ReplicatedEventsCoordinator eventsCoordinator){
    this.replicatedEventsCoordinator = eventsCoordinator;
  }

}
