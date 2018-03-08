package com.google.gerrit.common;

/**
 * This is a wrapper for the delete project message to be replicated,
 *
 */
public class ProjectInfoWrapper {
  public String projectName;
  public boolean preserve;
  public String taskUuid;
  public long eventTimestamp;
  public String nodeIdentity;
  public transient boolean replicated = false;

  public ProjectInfoWrapper(String projectName, boolean preserve, String taskUuid, String nodeIdentity) {
    this.projectName = projectName;
    this.preserve = preserve;
    this.taskUuid = taskUuid;
    this.eventTimestamp = System.currentTimeMillis();
    this.nodeIdentity = nodeIdentity;
  }

  public ProjectInfoWrapper() {}

  public void setNodeIdentity(String nodeIdentity) {
    this.nodeIdentity = nodeIdentity;
  }

  @Override
  public String toString() {
    return "ProjectInfoWrapper{" +
        "projectName='" + projectName + '\'' +
        ", preserve=" + preserve +
        ", taskUuid='" + taskUuid + '\'' +
        ", eventTimestamp=" + eventTimestamp +
        ", nodeIdentity='" + nodeIdentity + '\'' +
        ", replicated=" + replicated +
        '}';
  }
}
