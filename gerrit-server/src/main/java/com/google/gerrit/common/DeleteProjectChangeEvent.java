package com.google.gerrit.common;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Id;
import com.google.gerrit.reviewdb.client.Project;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
  /**
   * This is an Event to allow us to pass around the changes that need to be deleted
   * on all nodes when we delete a Project
   *
   */
  public class DeleteProjectChangeEvent{
    public Project project;
    public boolean preserve;
    public int changes[];
    public String taskUuid;
    public transient boolean replicated = false;

    public DeleteProjectChangeEvent() {
    }

    public DeleteProjectChangeEvent(Project project, boolean preserve, List<Change.Id> changesToBeDeleted, String taskUuid) {
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
    public String toString() {
      return "DeleteProjectChangeEvent [project=" + project + ", preserve="
          + preserve + ", changes=" + Arrays.toString(changes) + ", taskUuid="
          + taskUuid + "]";
    }

}
