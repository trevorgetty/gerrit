package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which informs the caller to move the entire events file into the failed folder.
 * This allows this file to be investigated and replayed later easily simply by moving back into the events incoming
 * directory.
 */
public class ReplicatedEventsMoveToFailedDirectory extends RuntimeException {

  public ReplicatedEventsMoveToFailedDirectory(final String message) {
    super(message);
  }


  public ReplicatedEventsMoveToFailedDirectory(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsMoveToFailedDirectory(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
