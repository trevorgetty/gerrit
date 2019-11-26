
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

import com.google.gerrit.reviewdb.client.Account;

import java.io.Serializable;

/**
 * Event to cover replication of the Account Index events
 * Account Index
 * There are several situations in which Gerrit needs to query accounts, e.g.:
 * For sending email notifications to project watchers.
 * For reviewer suggestions.
 * Accessing the account data in Git is not fast enough for account queries, since it
 * requires accessing all user branches and parsing all files in each of them.
 * To overcome this Gerrit has a secondary index for accounts. The account index is either based
 * on Lucene or Elasticsearch.
 *
 * Accounts are automatically reindexed on any update. The Index Account REST endpoint allows to
 * reindex an account manually. In addition the reindex program can be used to reindex all accounts offline.
 */
public class AccountUserIndexEvent extends AccountIndexEventBase {
  public Account.Id id;

  public AccountUserIndexEvent(Account.Id id, String nodeIdentity) {
    super(nodeIdentity);
    this.id=id;
  }
  @Override
  public Serializable getIdentifier(){
    return this.id;
  }

  @Override
  public String toString() {
    return String.format("AccountUserIndexEvent{AccountID=%s, eventTimestamp=%s, nodeIdentity=%s}",
        id, eventTimestamp, nodeIdentity);
  }
}
