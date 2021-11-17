package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which allows us to know when we have an error we think we can recover from as its
 * transient.
 * These are when we call to reindex or perform an operation for an event, but it returns a normal failure
 * response and not an exception - this way we can use normal backoff to retry this failure later on.
 */
public class ReplicatedEventsTransientException extends RuntimeException {

  public ReplicatedEventsTransientException(final String message) {
    super(message);
  }


  public ReplicatedEventsTransientException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsTransientException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
