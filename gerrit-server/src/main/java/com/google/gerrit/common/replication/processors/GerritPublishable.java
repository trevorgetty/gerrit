package com.google.gerrit.common.replication.processors;

import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

public interface GerritPublishable {
  void publishIncomingReplicatedEvents(EventWrapper newEvent);

  /**
   * Stop is used to call unsubscribe at appropriate time.. But as it passes in the this pointer I needed
   * to keep it abstract...
   */
  void stop();
  void subscribeEvent(GerritPublishable toCall);
  void unsubscribeEvent(GerritPublishable toCall);
}
