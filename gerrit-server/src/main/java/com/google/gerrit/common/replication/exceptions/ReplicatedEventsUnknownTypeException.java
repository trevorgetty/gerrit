package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which allows us to know when we have an error we can't recover from, examples
 * are class not found, or when we have send an event to the wrong processor.  It can be caught and we can
 * process the remainder of the file and just record this failure and any others in the failed event file.
 */
public class ReplicatedEventsUnknownTypeException extends RuntimeException {

  public ReplicatedEventsUnknownTypeException(final String message) {
    super(message);
  }


  public ReplicatedEventsUnknownTypeException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsUnknownTypeException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
