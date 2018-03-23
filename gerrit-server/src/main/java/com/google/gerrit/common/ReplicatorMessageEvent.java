package com.google.gerrit.common;

/**
 * This event is passed to the Git Multisite replicator to communicate
 * a status message.
 */
public class ReplicatorMessageEvent{
  public String project;
  public String status;
  public String prefix;
  public long eventTimestamp;
  public String nodeIdentity;


  public ReplicatorMessageEvent(String project, String status, String prefix, String nodeIdentity) {
    this.project = project;
    this.status = status;
    this.prefix = prefix;
    this.eventTimestamp = System.currentTimeMillis();
    this.nodeIdentity = nodeIdentity;
  }

  public void setNodeIdentity(String nodeIdentity) {
    this.nodeIdentity = nodeIdentity;
  }


  @Override
  public String toString() {
    return "ReplicatorMessageEvent{" +
        "project='" + project + '\'' +
        ", status='" + status + '\'' +
        ", prefix='" + prefix + '\'' +
        ", eventTimestamp=" + eventTimestamp +
        ", nodeIdentity='" + nodeIdentity + '\'' +
        '}';
  }
}
