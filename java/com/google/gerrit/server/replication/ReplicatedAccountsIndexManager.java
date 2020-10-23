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
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;
import java.io.Serializable;

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
public class ReplicatedAccountsIndexManager implements Replicator.GerritPublishable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson gson = new Gson();

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
  public void replicateReindex(Serializable identifier) {

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


  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean result = false;

    if (newEvent == null) {
      logger.atFine().log("RC : Received null event");
      return false;
    }
    //If originator is a ACCOUNT_GROUP_INDEX_EVENT or ACCOUNT_USER_INDEX_EVENT.
    if (newEvent.getEventOrigin() == originator) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        //Making the call to reindexAccountGroup for the local site.
        //i.e we don't need to make a replicated reindex now as we are receiving the
        //incoming replicated event to reindex.
        result = reindexAccount(newEvent, eventClass);
      } catch (ClassNotFoundException e) {
        logger.atSevere().withCause(e).log("RC Account event reindex has been lost. Could not find %s", newEvent.getClassName());
      }
    }
    return result;
  }

  /**
   * Local reindex of the AccountUser or AccountGroup event.
   *
   * @param newEvent
   * @param eventClass
   * @return
   */
  private boolean reindexAccount(EventWrapper newEvent, Class<?> eventClass) {
    try {
      AccountIndexEventBase indexEventBase = (AccountIndexEventBase) gson.fromJson(newEvent.getEvent(), eventClass);
      indexer.indexNoRepl(indexEventBase.getIdentifier());
      return true;
    } catch (JsonSyntaxException | IOException e) {
      logger.atSevere().withCause(e).log("RC AccountGroup reindex, Could not decode json event %s", newEvent.toString());
      return false;
    }
  }
}
