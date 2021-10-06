package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which allows us to know when the replicated DB is not up to date and we
 * should re-queue our events for later processing.
 */
public class ReplicatedEventsDBNotUpToDateException extends RuntimeException {

  public ReplicatedEventsDBNotUpToDateException(final String message) {
    super(message);
  }


  public ReplicatedEventsDBNotUpToDateException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsDBNotUpToDateException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
