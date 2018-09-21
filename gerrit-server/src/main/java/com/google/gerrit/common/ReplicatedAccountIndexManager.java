
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
 
package com.google.gerrit.common;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.events.EventWrapper;
import com.google.gerrit.server.index.account.AccountIndexerImpl;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * This class is for replication of Account Index events
 * initialised by com.google.gerrit.server.index.account.AccountIndexerImpl
 *
 * Local calls for reindex on an account are queued for replication to other Sites
 * Index events received call indexRepl
 * to update locally without calling replication again
 *
 * @author Philip Moore
 */
public class ReplicatedAccountIndexManager implements Replicator.GerritPublishable {
  private static final Logger log = LoggerFactory.getLogger(
      ReplicatedAccountIndexManager.class);
  private static final Gson gson = new Gson();
  private AccountIndexerImpl indexer;
  private static ReplicatedAccountIndexManager instance = null;
  private static Replicator replicatorInstance = null;


  public static void replicateAccountReindex(Account.Id id){
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(id.get(),replicatorInstance.getThisNodeIdentity());
    log.debug("RC AccountReIndex reindex being replicated for ID: {} ",id.get());
    replicatorInstance.queueEventForReplication(new EventWrapper(accountIndexEvent));
  }

  public static synchronized void initAccountIndexer(AccountIndexerImpl indexer){
    if (instance == null) {
      instance = new ReplicatedAccountIndexManager(indexer);
      log.info("RC ReplicatedAccountIndexManager New instance created");
      replicatorInstance = Replicator.getInstance(true);
      replicatorInstance.subscribeEvent(EventWrapper.Originator.ACCOUNT_INDEX_EVENT, instance);
    }
  }

  @Override
  public boolean publishIncomingReplicatedEvents(
      EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.originator ==  EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.className);
          result = reindexAccount(newEvent, eventClass);
      } catch(ClassNotFoundException e) {
        log.error("RC AccountReIndex has been lost. Could not find {}",newEvent.className,e);
      }
    } else if (newEvent != null && newEvent.originator !=  EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      log.error("RC AccountReIndex event has been sent here but originator is not the right one ({})",newEvent.originator);
    }
    return result;
  }

  public ReplicatedAccountIndexManager(
      AccountIndexerImpl indexer) {
    this.indexer=indexer;
  }

  private boolean reindexAccount(EventWrapper newEvent, Class<?> eventClass) {
    AccountIndexEvent originalEvent = null;
    boolean result = false;
    try {
      originalEvent = (AccountIndexEvent) gson.fromJson(newEvent.event, eventClass);
      indexer.indexRepl(new Account.Id(originalEvent.indexNumber));
      return true;
    } catch (JsonSyntaxException je) {
      log.error("RC AccountReIndex, Could not decode json event {}", newEvent.toString(), je);
      return result;
    } catch (IOException ie){
      log.error("RC AccountReindex issue hit while carrying out reindex of {}",newEvent.toString(),ie);
      return result;
    }
  }
}
