package com.google.gerrit.common;

/**
 * This is a wrapper for the delete project message to be replicated,
 *
 */
public class ProjectInfoWrapper {
  public String projectName;
  public boolean preserve;
  public String taskUuid;
  public transient boolean replicated = false;

  public ProjectInfoWrapper(String projectName, boolean preserve, String taskUuid) {
    this.projectName = projectName;
    this.preserve = preserve;
    this.taskUuid = taskUuid;
  }

  public ProjectInfoWrapper() {
  }

  @Override
  public String toString() {
    return "ProjectInfoWrapper{" + "projectName=" + projectName + ", preserve=" + preserve + ", taskUuid=" + taskUuid + '}';
  }

}
