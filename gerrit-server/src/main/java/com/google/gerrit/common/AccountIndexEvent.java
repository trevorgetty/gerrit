
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

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

/**
 * Event to cover replication of the Account Index events
 */
public class AccountIndexEvent extends ReplicatedEvent {
  public int indexNumber;

  public AccountIndexEvent(final String nodeIdentity) {
    super(nodeIdentity);
  }

  public AccountIndexEvent(int id, final String nodeIdentity) {
    super(nodeIdentity);
    this.indexNumber=id;
  }

  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("AccountIndexEvent{");
    sb.append("indexNumber=").append(indexNumber);
    sb.append(", ").append(super.toString());
    sb.append('}');
    return sb.toString();
  }
}
