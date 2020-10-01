
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
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

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Id;
import com.google.gerrit.reviewdb.client.Project;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
   * This is an Event to allow us to pass around the changes that need to be deleted
   * on all nodes when we delete a Project
   *
   */
public class DeleteProjectChangeEvent extends ReplicatedEvent {
  public Project project;
  public boolean preserve;
  public int changes[];
  public String taskUuid;
  public transient boolean replicated = false;

  public DeleteProjectChangeEvent(final String nodeIdentity) {
    super(nodeIdentity);
  }

  public DeleteProjectChangeEvent(Project project, boolean preserve, List<Change.Id> changesToBeDeleted,
                                  final String taskUuid, final String nodeIdentity) {
    super(nodeIdentity);
    this.project = project;
    this.preserve = preserve;
    this.taskUuid = taskUuid;
    changes = new int[changesToBeDeleted.size()];
    int index = 0;
    for (Iterator<Id> iterator = changesToBeDeleted.iterator(); iterator.hasNext();) {
      changes[index++] = iterator.next().get();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) { return true; }
    if (!(o instanceof DeleteProjectChangeEvent)) { return false; }

    DeleteProjectChangeEvent that = (DeleteProjectChangeEvent) o;

    if (preserve != that.preserve) { return false; }
    if (replicated != that.replicated) { return false; }
    if (!Objects.equals(project, that.project)) { return false; }
    if (!Arrays.equals(changes, that.changes)) { return false; }
    return Objects.equals(taskUuid, that.taskUuid);
  }

  @Override
  public int hashCode() {
    int result = project != null ? project.hashCode() : 0;
    result = 31 * result + (preserve ? 1 : 0);
    result = 31 * result + Arrays.hashCode(changes);
    result = 31 * result + (taskUuid != null ? taskUuid.hashCode() : 0);
    result = 31 * result + (replicated ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", DeleteProjectChangeEvent.class.getSimpleName() + "[", "]")
            .add("project=" + project)
            .add("preserve=" + preserve)
            .add("changes=" + Arrays.toString(changes))
            .add("taskUuid='" + taskUuid + "'")
            .add("replicated=" + replicated)
            .add(super.toString())
            .toString();
  }
}
