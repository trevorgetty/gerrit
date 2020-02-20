
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.gerritconsoleapi.exceptions;

/**
 * Class to allow an exception to be thrown which only logs out a message and terminates the application.
 * It will not show a stack trace by default.
 */
public class LogAndExitException  extends RuntimeException {

  private int exitCode = 0;

  public LogAndExitException(final String exceptionMessage) {
      super(exceptionMessage);
  }

  public LogAndExitException(final String exceptionMessage, final Throwable ex) {
    super(exceptionMessage, ex);
  }

  public LogAndExitException(final String exceptionMessage , final int commandExitCode ) {
    super(exceptionMessage);
    exitCode = commandExitCode;
  }

  public LogAndExitException(final String exceptionMessage, final Throwable ex, final int commandExitCode) {
    super(exceptionMessage, ex);
    exitCode = commandExitCode;
  }

  public int getExitCode() {
    return exitCode;
  }
}
