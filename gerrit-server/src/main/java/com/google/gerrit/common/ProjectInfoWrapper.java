
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
 * This is a wrapper for the delete project message to be replicated,
 *
 */
public class ProjectInfoWrapper extends ReplicatedEvent {
  public String projectName;
  public boolean preserve;
  public String taskUuid;
  public transient boolean replicated = false;

  public ProjectInfoWrapper(String nodeIdentity) {
    super(nodeIdentity);
  }

  public ProjectInfoWrapper(String projectName, boolean preserve, final String taskUuid, final String nodeIdentity) {
    super(nodeIdentity);
    this.projectName = projectName;
    this.preserve = preserve;
    this.taskUuid = taskUuid;
  }

  public void setNodeIdentity(String nodeIdentity) {
    super.setNodeIdentity(nodeIdentity);
  }


  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("ProjectInfoWrapper{");
    sb.append("projectName='").append(projectName).append('\'');
    sb.append(", preserve=").append(preserve);
    sb.append(", taskUuid='").append(taskUuid).append('\'');
    sb.append(", '").append(super.toString()).append('\'');
    sb.append(", replicated=").append(replicated);
    sb.append('}');
    return sb.toString();
  }
}
