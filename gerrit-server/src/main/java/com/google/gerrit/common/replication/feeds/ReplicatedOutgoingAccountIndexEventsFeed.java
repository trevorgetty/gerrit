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

  private static ReplicatedOutgoingAccountIndexEventsFeed INSTANCE;

  private ReplicatedOutgoingAccountIndexEventsFeed(ReplicatedEventsCoordinator eventsCoordinator) {
    super(eventsCoordinator);
  }

  //Get singleton instance
  public static ReplicatedOutgoingAccountIndexEventsFeed getInstance(ReplicatedEventsCoordinator eventsCoordinator) {
    if(INSTANCE == null) {
      INSTANCE = new ReplicatedOutgoingAccountIndexEventsFeed(eventsCoordinator);
      SingletonEnforcement.registerClass(ReplicatedOutgoingAccountIndexEventsFeed.class);
    }

    return INSTANCE;
  }

  public void replicateAccountReindex(Account.Id id) throws IOException {
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(id.get(), replicatedEventsCoordinator.getThisNodeIdentity());
    log.debug("RC AccountReIndex reindex being replicated for ID: {} ", id.get());
    replicatedEventsCoordinator.queueEventForReplication(GerritEventFactory.createReplicatedAccountIndexEvent(
        "All-Users", accountIndexEvent, ACCOUNT_INDEX_EVENT));
  }

}
