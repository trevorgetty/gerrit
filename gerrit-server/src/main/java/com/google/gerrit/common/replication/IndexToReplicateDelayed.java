package com.google.gerrit.common.replication;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * Holds information needed to index the change on the nodes, and also to make it replicate across the other nodes
 */
public final class IndexToReplicateDelayed implements Delayed {
  public final int indexNumber;
  public final String projectName;
  public final Timestamp lastUpdatedOn;
  public final long currentTime;
  public static final long STANDARD_REINDEX_DELAY_MS = 30*1000; // 30 seconds

  public IndexToReplicateDelayed(int indexNumber, String projectName, Timestamp lastUpdatedOn) {
    this.indexNumber = indexNumber;
    this.projectName = projectName;
    this.lastUpdatedOn = new Timestamp(lastUpdatedOn.getTime());
    this.currentTime = System.currentTimeMillis();
  }

  private IndexToReplicateDelayed(IndexToReplicate index) {
    this.indexNumber = index.indexNumber;
    this.projectName = index.projectName;
    this.lastUpdatedOn = index.lastUpdatedOn;
    this.currentTime = index.currentTime;
  }

  private static IndexToReplicateDelayed shallowCopyOf(IndexToReplicate indexToReplicate) {
    return new IndexToReplicateDelayed(indexToReplicate);
  }

  @Override
  public String toString() {
    return "IndexToReplicate{" + "indexNumber=" + indexNumber + ", projectName=" + projectName + ", lastUpdatedOn=" + lastUpdatedOn + ", currentTime=" + currentTime + '}';
  }

  @Override
  public long getDelay(TimeUnit unit) {
    return unit.convert(STANDARD_REINDEX_DELAY_MS - (System.currentTimeMillis()-currentTime), TimeUnit.MILLISECONDS);
  }

  @Override
  public int compareTo(Delayed o) {
    int result;
    if (o == null) {
      result = 1;
    } else {
      long diff = this.currentTime - ((IndexToReplicateDelayed) o).currentTime ;
      result = diff < 0 ? -1: diff==0? (this.indexNumber - ((IndexToReplicateDelayed) o).indexNumber):+1;
    }
    return result;
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
