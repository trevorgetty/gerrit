
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

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Id;
import com.google.gerrit.reviewdb.client.Project;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
  /**
   * This is an Event to allow us to pass around the changes that need to be deleted
   * on all nodes when we delete a Project
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

    public DeleteProjectChangeEvent(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, final String taskUuid, final String nodeIdentity) {
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

    public void setNodeIdentity(String nodeIdentity) {
      super.setNodeIdentity(nodeIdentity);
    }

    @Override public String toString() {
      final StringBuilder sb = new StringBuilder("DeleteProjectChangeEvent{");
      sb.append("project=").append(project);
      sb.append(", preserve=").append(preserve);
      sb.append(", changes=").append(Arrays.toString(changes));
      sb.append(", taskUuid='").append(taskUuid).append('\'');
      sb.append(", ").append(super.toString()).append('\'');
      sb.append(", replicated=").append(replicated);
      sb.append('}');
      return sb.toString();
    }
  }
