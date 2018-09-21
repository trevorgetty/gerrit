
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

/**
 * Event to cover replication of the Account Index events
 *
 */
public class AccountIndexEvent {
  public int indexNumber;
  public long eventTimestamp;
  public String nodeIdentity;

  public AccountIndexEvent() {
  }

  public AccountIndexEvent(int id, String nodeIdentity) {
    this.eventTimestamp = System.currentTimeMillis();
    this.nodeIdentity = nodeIdentity;
    this.indexNumber=id;
  }

  public void setNodeIdentity(String nodeIdentity) {
    this.nodeIdentity = nodeIdentity;
  }

  @Override
  public String toString() {
    return "AccountIndexEvent{" +
        "AccountID=" + indexNumber +
        ", eventTimestamp=" + eventTimestamp +
        ", nodeIdentity='" + nodeIdentity + '\'' +
        '}';
  }
}
