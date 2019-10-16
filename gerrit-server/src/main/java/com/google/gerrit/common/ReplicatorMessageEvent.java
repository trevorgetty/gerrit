
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
