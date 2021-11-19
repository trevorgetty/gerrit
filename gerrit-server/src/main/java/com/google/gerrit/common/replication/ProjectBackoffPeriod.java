package com.google.gerrit.common.replication;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allow a project to be backed off from processing for a period of time.
 * Record projectName, numFailures and start time of each failure.
 */
public class ProjectBackoffPeriod {

  private AtomicInteger numFailureRetries;
  private long lastFailureTimeInMs; // the last failures start time, to know when we can schedule again.
  private final String projectName;
  private final ReplicatedConfiguration replicatedConfiguration; // holds backoff period information.

  public ProjectBackoffPeriod(String projectName, ReplicatedConfiguration replicatedConfiguration) {
    this.projectName = projectName;
    this.lastFailureTimeInMs = System.currentTimeMillis();
    this.numFailureRetries = new AtomicInteger(1);
    this.replicatedConfiguration = replicatedConfiguration;
  }

  public int getNumFailureRetries() {
    return numFailureRetries.get();
  }

  public void setNumFailureRetries(int numFailureRetries) {
    this.numFailureRetries.set(numFailureRetries);
  }

  public long getStartTimeInMs() {
    return lastFailureTimeInMs;
  }

  public void setStartTimeInMs(long startTimeInMs) {
    this.lastFailureTimeInMs = startTimeInMs;
  }

  public String getProjectName() {
    return projectName;
  }

  // Used to update the failure retry counter, and record when this failure happened in an easy manner
  public void updateFailureInformation() {
    setStartTimeInMs(System.currentTimeMillis());
    final int curNumFailures = numFailureRetries.get();
    if (curNumFailures >= replicatedConfiguration.getMaxIndexBackoffRetries()) {
      // Keeping the counter at max retry count, so we keep backing off at the ceiling period, and not
      // jump back down to smallest value.
      return;
    }
    numFailureRetries.incrementAndGet();
  }

  public long getCurrentBackoffPeriodMs() {
    // The backoff period is based on our initial period, and the number of retries they have
    // already experienced.
    // N.B. Use our calculated backoff sequence and just index into it.
    return replicatedConfiguration.getIndexBackoffPeriodMs(numFailureRetries.get());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProjectBackoffPeriod)) return false;
    ProjectBackoffPeriod that = (ProjectBackoffPeriod) o;
    return numFailureRetries == that.numFailureRetries &&
        lastFailureTimeInMs == that.lastFailureTimeInMs &&
        Objects.equals(projectName, that.projectName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(numFailureRetries, lastFailureTimeInMs, projectName);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ProjectBackoffPeriod{");
    sb.append("numFailureRetries=").append(numFailureRetries.get());
    sb.append(", lastFailureTimeInMs=").append(lastFailureTimeInMs);
    sb.append(", projectName='").append(projectName).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
