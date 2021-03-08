package com.google.gerrit.server.replication;

import com.google.gerrit.reviewdb.client.Project;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.Serializable;

public class ProjectIndexEvent extends ReplicatedEvent {
  public Project.NameKey nameKey;
  private boolean deleteIndex = false;

  public ProjectIndexEvent(final String nodeIdentity) {
    super(nodeIdentity);
  }

  public ProjectIndexEvent(Project.NameKey nameKey,
                           final String nodeIdentity, final boolean deleteIndex) {
    super(nodeIdentity);
    this.nameKey = nameKey;
    this.deleteIndex = deleteIndex;
  }

  public Project.NameKey getIdentifier() {
    return this.nameKey;
  }

  public boolean isDeleteIndex() {
    return deleteIndex;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ProjectIndexEvent{");
    sb.append("NameKey=").append(nameKey);
    sb.append("DeleteFromIndex=").append(deleteIndex);
    sb.append(", ").append(super.toString());
    sb.append('}');
    return sb.toString();
  }
}
