
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

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.Objects;
import java.util.StringJoiner;

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

  @Override
  public boolean equals(Object o) {
    if (this == o) { return true; }
    if (!(o instanceof ProjectInfoWrapper)) { return false; }

    ProjectInfoWrapper that = (ProjectInfoWrapper) o;

    if (preserve != that.preserve) { return false; }
    if (replicated != that.replicated) { return false; }
    if (!Objects.equals(projectName, that.projectName)) { return false; }
    return Objects.equals(taskUuid, that.taskUuid);
  }

  @Override
  public int hashCode() {
    int result = projectName != null ? projectName.hashCode() : 0;
    result = 31 * result + (preserve ? 1 : 0);
    result = 31 * result + (taskUuid != null ? taskUuid.hashCode() : 0);
    result = 31 * result + (replicated ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", ProjectInfoWrapper.class.getSimpleName() + "[", "]")
            .add("projectName='" + projectName + "'")
            .add("preserve=" + preserve)
            .add("taskUuid='" + taskUuid + "'")
            .add("replicated=" + replicated)
            .add(super.toString())
            .toString();
  }
}
