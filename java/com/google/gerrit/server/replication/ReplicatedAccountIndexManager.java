
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
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
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.index.account.AccountIndexerImpl;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;

/**
 * This class is for replication of Account Index events
 * initialised by com.google.gerrit.server.index.account.AccountIndexerImpl
 * <p>
 * Local calls for reindex on an account are queued for replication to other Sites
 * Index events received call indexRepl
 * to update locally without calling replication again
 *
 * @author Philip Moore
 */
public class ReplicatedAccountIndexManager implements Replicator.GerritPublishable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson gson = new Gson();
  private AccountIndexerImpl indexer;
  private static ReplicatedAccountIndexManager instance = null;
  private static Replicator replicatorInstance = null;


  public static void replicateAccountReindex(Account.Id id) {
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(id.get(), replicatorInstance.getThisNodeIdentity());
    logger.atFiner().log("RC AccountReIndex reindex being replicated for ID: %s ", id.get());
    replicatorInstance.queueEventForReplication(new EventWrapper("All-Users", accountIndexEvent));
  }

  public static synchronized void initAccountIndexer(AccountIndexerImpl indexer) {
    if (instance == null) {
      // Check for override behaviour allowing vanilla gerrit behaviour without gitms.
      if (Replicator.isReplicationDisabled()) {
        logger.atInfo().log("RC Not creating ReplicatedAccountIndexManager as GitMS is disabled in Gerrit.");
        return;
      }

      instance = new ReplicatedAccountIndexManager(indexer);
      logger.atInfo().log("RC ReplicatedAccountIndexManager New instance created");
      replicatorInstance = Replicator.getInstance(true);
      replicatorInstance.subscribeEvent(EventWrapper.Originator.ACCOUNT_INDEX_EVENT, instance);
    }
  }

  @Override
  public boolean publishIncomingReplicatedEvents(
      EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.originator == EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.className);
        result = reindexAccount(newEvent, eventClass);
      } catch (ClassNotFoundException e) {
        logger.atSevere().withCause(e).log("RC AccountReIndex has been lost. Could not find %s", newEvent.className);
      }
    } else if (newEvent != null && newEvent.originator != EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      logger.atSevere().log("RC AccountReIndex event has been sent here but originator is not the right one (%s)", newEvent.originator);
    }
    return result;
  }

  public ReplicatedAccountIndexManager(
      AccountIndexerImpl indexer) {
    this.indexer = indexer;
  }

  private boolean reindexAccount(EventWrapper newEvent, Class<?> eventClass) {
    AccountIndexEvent originalEvent = null;
    boolean result = false;
    try {
      originalEvent = (AccountIndexEvent) gson.fromJson(newEvent.event, eventClass);
      indexer.indexNoRepl(new Account.Id(originalEvent.indexNumber));
      return true;
    } catch (JsonSyntaxException e) {
      logger.atSevere().withCause(e).log("RC AccountReIndex, Could not decode json event %s", newEvent.toString());
      return result;
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("RC AccountReindex issue hit while carrying out reindex of %s", newEvent.toString());
      return result;
    }
  }
}
