package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which informs the caller to move the entire events file into the failed folder.
 * This allows this file to be investigated and replayed later easily simply by moving back into the events incoming
 * directory.
 */
public class ReplicatedEventsImmediateFailWithoutBackoffException extends RuntimeException {

  public ReplicatedEventsImmediateFailWithoutBackoffException(final String message) {
    super(message);
  }


  public ReplicatedEventsImmediateFailWithoutBackoffException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsImmediateFailWithoutBackoffException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
