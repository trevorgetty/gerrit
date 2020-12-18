package com.google.gerrit.server.replication;

import com.google.gerrit.reviewdb.client.Project;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.io.Serializable;

public class ProjectIndexEvent extends ReplicatedEvent {
		public Project.NameKey nameKey;

		public ProjectIndexEvent(final String nodeIdentity) {
				super(nodeIdentity);
		}

		public ProjectIndexEvent(Project.NameKey nameKey, final String nodeIdentity) {
			super(nodeIdentity);
			this.nameKey = nameKey;
		}

		public Project.NameKey getIdentifier() {
			return this.nameKey;
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("ProjectIndexEvent{");
			sb.append("NameKey=").append(nameKey);
			sb.append(", ").append(super.toString());
			sb.append('}');
			return sb.toString();
		}
}
