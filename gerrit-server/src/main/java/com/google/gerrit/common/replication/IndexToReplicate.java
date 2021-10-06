package com.google.gerrit.common.replication;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.sql.Timestamp;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Holds information needed to index the change on the nodes, and also to make it replicate across the other nodes
 */
public class IndexToReplicate extends ReplicatedEvent {
  public int indexNumber;
  public String projectName;
  public Timestamp lastUpdatedOn;
  public long currentTime;
  public int timeZoneRawOffset;
  public boolean delete;


  public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, String thisNodeIdentity) {
    super(thisNodeIdentity);
    final long currentTimeMs = super.getEventTimestamp();
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), false);
  }

  public IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, boolean delete, String thisNodeIdentity) {
    super(thisNodeIdentity);
    final long currentTimeMs = super.getEventTimestamp();
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTimeMs, getRawOffset(currentTimeMs), delete);
  }

  public IndexToReplicate(IndexToReplicateDelayed delayed, String thisNodeId) {
    this(delayed.indexNumber, delayed.projectName, delayed.lastUpdatedOn, delayed.currentTime, getRawOffset(delayed.currentTime), false, thisNodeId);
  }

  public IndexToReplicate(IndexToReplicate index, String thisNodeId){
    this(index.indexNumber, index.projectName, index.lastUpdatedOn, index.currentTime, index.timeZoneRawOffset, index.delete, thisNodeId);
  }

  protected IndexToReplicate(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset, boolean delete, String thisNodeIdentity) {
    super(thisNodeIdentity);
    setBaseMembers(indexNumber, projectName, lastUpdatedOn, currentTime, rawOffset, delete);
  }

  private void setBaseMembers(int indexNumber, String projectName, Timestamp lastUpdatedOn, long currentTime, int rawOffset, boolean delete) {
    this.indexNumber = indexNumber;
    this.projectName = projectName;
    this.lastUpdatedOn = new Timestamp(lastUpdatedOn.getTime());
    this.currentTime = currentTime;
    this.timeZoneRawOffset = rawOffset;
    this.delete = delete;
  }

  public static int getRawOffset(final long currentTime) {
    TimeZone tzDefault = TimeZone.getDefault();

    if (tzDefault.inDaylightTime(new Date(currentTime))) {
      return TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings();
    }

    return TimeZone.getDefault().getRawOffset();
  }

  @Override public String toString() {
    final StringBuilder sb = new StringBuilder("IndexToReplicate{");
    sb.append("indexNumber=").append(indexNumber);
    sb.append(", projectName='").append(projectName).append('\'');
    sb.append(", lastUpdatedOn=").append(lastUpdatedOn);
    sb.append(", currentTime=").append(currentTime);
    sb.append(", timeZoneRawOffset=").append(timeZoneRawOffset);
    sb.append(", delete=").append(delete);
    sb.append(", ").append(super.toString());
    sb.append('}');
    return sb.toString();
  }

  @Override
  public int hashCode() {
    int hash = 3;
    hash = 41 * hash + this.indexNumber;
    hash = 41 * hash + Objects.hashCode(this.projectName);
    hash = 41 * hash + Objects.hashCode(this.lastUpdatedOn);
    hash = 41 * hash + (int) (this.currentTime ^ (this.currentTime >>> 32));
    return hash;
  }


  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final IndexToReplicate other = (IndexToReplicate) obj;
    if (this.indexNumber != other.indexNumber) {
      return false;
    }
    if (!Objects.equals(this.projectName, other.projectName)) {
      return false;
    }
    if (!Objects.equals(this.lastUpdatedOn, other.lastUpdatedOn)) {
      return false;
    }
    return this.currentTime == other.currentTime;
  }
}
