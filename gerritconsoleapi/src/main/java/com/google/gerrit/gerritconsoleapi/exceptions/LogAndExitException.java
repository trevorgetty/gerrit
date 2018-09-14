package com.google.gerrit.gerritconsoleapi.exceptions;

/**
 * Class to allow an exception to be thrown which only logs out a message and terminates the application.
 * It will not show a stack trace by default.
 */
public class LogAndExitException extends Exception {

  public LogAndExitException(final String exceptionMessage) {
      super(exceptionMessage);
  }

  public LogAndExitException(final String exceptionMessage, final Throwable ex) {
    super(exceptionMessage, ex);
  }
}
