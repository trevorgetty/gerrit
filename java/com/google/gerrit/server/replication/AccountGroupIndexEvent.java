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

import com.google.gerrit.reviewdb.client.AccountGroup;

import java.io.Serializable;

/**
 * Event to cover replication of the Group Account index events
 * In Gerrit, we assign permissions to groups of accounts.
 * These groups can be provided by an external system such as LDAP,
 * but Gerrit also has a group system built-in ("internal groups")
 * Starting from 2.16, these internal groups are fully stored in NoteDb.
 *
 * A group is characterized by the following information:
 * list of members (accounts)
 * list of subgroups
 * properties
 * visibleToAll
 * group owner
 * Groups are keyed by the following unique identifiers:
 * GroupID, the former database key (a sequential number)
 * UUID, an opaque identifier. Internal groups use a 40 byte hex string as UUID
 * Name: Gerrit enforces that group names are unique
 *
 */
public class AccountGroupIndexEvent extends AccountIndexEventBase {
  public AccountGroup.UUID uuid;

  public AccountGroupIndexEvent(final String nodeIdentity) {
    super(nodeIdentity);
  }

  public AccountGroupIndexEvent(AccountGroup.UUID uuid, final String nodeIdentity) {
    super(nodeIdentity);
    this.uuid=uuid;
  }

  @Override
  public Serializable getIdentifier(){
    return this.uuid;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("AccountGroupIndexEvent{");
    sb.append("UUID=").append(uuid);
    sb.append(", ").append(super.toString());
    sb.append('}');
    return sb.toString();
  }
}
