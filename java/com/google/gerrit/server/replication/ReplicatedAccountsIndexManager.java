/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.index.account.AccountIndexerImpl;
import com.google.gerrit.server.index.group.GroupIndexerImpl;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_GROUP_INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_USER_INDEX_EVENT;

/**
 * This class is for replication of Group Index events
 * initialised by com.google.gerrit.server.index.account.GroupIndexerImpl
 * <p>
 * Local calls for reindex on an group are queued for replication to other Sites
 * Index events received on those sites call indexRepl to update locally without
 * calling replication again
 *
 * @author Ronan Conway
 */
public class ReplicatedAccountsIndexManager implements ReplicatedEventProcessor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicatedAccountIndexer indexer = null;
  private Originator originator = null;

  /**
   * Both AccountIndexerImpl and GroupIndexerImpl inherit our
   * ReplicatedAccountIndexer. This means we can have a single indexer to be used by both.
   * Originator types can be ACCOUNT_GROUP_INDEX_EVENT or ACCOUNT_USER_INDEX_EVENT
   * @param indexer
   * @param event
   */
  public void setIndexer(ReplicatedAccountIndexer indexer, Originator event) {
    this.indexer = indexer;
    originator = event;
    //Only subscribe for the instance if not already subscribed
    if(!replicatorInstance.isSubscribed(originator)) {
      Replicator.subscribeEvent(originator, this);
    }
  }

  // Using instance variables, to avoid contention / thread issues now this is a non-singleton class.
  private Replicator replicatorInstance = null;


  public interface Factory {

    /**
     * Returns a ReplicatedAccountsIndexManager instance.
     * @return
     */
    static ReplicatedAccountsIndexManager create() {
      if (Replicator.isReplicationDisabled()) {
        logger.atInfo().log("RC Not creating ReplicatedAccountGroupIndexManager as GitMS is disabled in Gerrit.");
        return null;
      }
      return new ReplicatedAccountsIndexManager();
    }

    /**
     * Set a group indexer for ReplicatedAccountsIndexManager instance
     * @param indexer
     * @return
     */
    static ReplicatedAccountsIndexManager create(GroupIndexerImpl indexer) {
      ReplicatedAccountsIndexManager replicatedAccountsIndexManager = create();
      if (replicatedAccountsIndexManager == null) {
        return null;
      }
      //We get the ReplicatedAccountsIndexManager instance using the create() method, then
      //we can set the indexer for the instance.
      replicatedAccountsIndexManager.setIndexer(indexer, ACCOUNT_GROUP_INDEX_EVENT);
      return replicatedAccountsIndexManager;
    }

    /**
     * Set a user account indexer for ReplicatedAccountsIndexManager instance
     * @param indexer
     * @return
     */
    static ReplicatedAccountsIndexManager create(AccountIndexerImpl indexer) {
      ReplicatedAccountsIndexManager replicatedAccountsIndexManager = create();
      if (replicatedAccountsIndexManager == null) {
        return null;
      }
      replicatedAccountsIndexManager.setIndexer(indexer, ACCOUNT_USER_INDEX_EVENT);
      return replicatedAccountsIndexManager;
    }
  }

  public ReplicatedAccountsIndexManager() {
    replicatorInstance = Replicator.getInstance(true);
    logger.atInfo().log("Created ReplicatedAccountsIndexManager");
  }

  /**
   * Queues either a AccountGroupIndexEvent or AccountUserIndexEvent event based on the identifier passed
   * to the method. The identifier is either an Account.Id or an AccountGroup.UUID.
   * The call to this method is either made from AccountIndexerImpl or GroupIndexerImpl
   * indexImplementation.
   *
   * @param identifier
   */
  public void replicateReindex(Serializable identifier) throws IOException {

    AccountIndexEventBase accountIndexEventBase = null;
    if (identifier instanceof Account.Id) {
      accountIndexEventBase = new AccountUserIndexEvent((Account.Id) identifier, replicatorInstance.getThisNodeIdentity());
      logger.atFine().log("RC Account User reindex being replicated for Id: %s ", identifier);
    } else if (identifier instanceof AccountGroup.UUID) {
      accountIndexEventBase = new AccountGroupIndexEvent((AccountGroup.UUID) identifier, replicatorInstance.getThisNodeIdentity());
      logger.atFine().log("RC Account Group reindex being replicated for UUID: %s ", identifier);
    }
    replicatorInstance.queueEventForReplication(
        GerritEventFactory.createReplicatedAccountIndexEvent("All-Users", accountIndexEventBase, originator));
  }


  /**
   * Process incoming replicated events for ACCOUNT_USER_INDEX_EVENT or
   * ACCOUNT_GROUP_INDEX_EVENT. We pass the incoming ReplicatedEvent to
   * reindexAccount so that a decision can be made to perform a local
   * reindex of account user / group indexes.
   * @param replicatedEvent cast to AccountIndexEventBase which is a parent
   *              class of AccountUserIndexEvent and AccountGroupIndexEvent
   *
   * @return true if we succeed in performing a local reindex of the
   * respective accounts index
   */
  @Override
  public boolean processIncomingReplicatedEvent(final ReplicatedEvent replicatedEvent) {
    return replicatedEvent != null && reindexAccount((AccountIndexEventBase) replicatedEvent);
  }

  /**
   * Local reindex of the AccountUser or AccountGroup event.
   * @return true|false based on whether the local reindex was performed or not.
   */
  private boolean reindexAccount(AccountIndexEventBase indexEventBase) {
    try {
      indexer.indexNoRepl(indexEventBase.getIdentifier());
      return true;
    } catch (IOException e) {
      logger.atSevere().log("Unable to perform local reindex on Node with identity %s",
          Objects.requireNonNull(Replicator.getInstance()).getThisNodeIdentity());
      return false;
    }
  }
}
