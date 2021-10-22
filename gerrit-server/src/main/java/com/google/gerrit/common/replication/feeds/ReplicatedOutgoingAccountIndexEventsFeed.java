package com.google.gerrit.common.replication.feeds;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.ConfigureReplication;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.reviewdb.client.Account;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_INDEX_EVENT;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedOutgoingAccountIndexEventsFeed extends ReplicatedOutgoingEventsFeedCommon {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedOutgoingAccountIndexEventsFeed.class);

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   * @param eventsCoordinator
   */
  public ReplicatedOutgoingAccountIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedOutgoingAccountIndexEventsFeed.class);
  }

  public void replicateAccountReindex(Account.Id id) throws IOException {
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(id.get(), replicatedEventsCoordinator.getThisNodeIdentity());
    log.debug("RC AccountReIndex reindex being replicated for ID: {} ", id.get());
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedAccountIndexEvent(
        "All-Users", accountIndexEvent, ACCOUNT_INDEX_EVENT));
  }

}
