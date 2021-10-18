package com.google.gerrit.common.replication.exceptions;

/**
 * Custom exception which allows us to know when the replicated DB is not up to date and we
 * should re-queue our events for later processing.
 */
public class ReplicatedEventsMissingChangeInformationException extends RuntimeException {

  public ReplicatedEventsMissingChangeInformationException(final String message) {
    super(message);
  }


  public ReplicatedEventsMissingChangeInformationException(final Throwable throwable) {
    super(throwable);
  }

  public ReplicatedEventsMissingChangeInformationException(final String message, final Throwable throwable) {
    super(message, throwable);
  }
}
