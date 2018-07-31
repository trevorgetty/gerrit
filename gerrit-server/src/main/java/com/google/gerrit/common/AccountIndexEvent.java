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
