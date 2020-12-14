
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.gerritconsoleapi;

import org.slf4j.Logger;

/**
 * Utility class for logging output using by the consoleapi jar.
 */
public abstract class Logging {

  private static boolean verbose = false;

  // Verbose error logging, write out error message and Stack trace to STDERR
  // classlogger arg is the slf4j logger from the class that wants to log a
  // message that could include a stack trace if --verbose was enabled. We
  // write the log message with classlogger to ensure that the log message stack trace has the
  // correct class name on it rather than this class name ie Logging
  public static void logerror(Logger classlogger, String s, Throwable e) {

    // We have passed in a throwable exception
    // verbose mode has to be enabled before we can log it
    // otherwise just log the error message string
    if ( isVerbose() ) {
      // Use logger from class that called this method
      classlogger.error(s, e);
    } else {
      classlogger.error(s);
    }

  }

  // Enable verbose mode
  // Verbose mode enables debug log level.
  // Verbose mode will log stack traces in addition to error message inside exception handling blocks
  public static void setVerbose() {
    //enable verbose command output
    verbose = true;

    // Set log4j log level to DEBUG
    org.apache.log4j.Logger log4j_logger = org.apache.log4j.Logger.getRootLogger();
    log4j_logger.setLevel(org.apache.log4j.Level.toLevel("DEBUG"));
  }

  // Is verbose mode enabled
  // This is used inside exception handling blocks where we need to detect if we
  // are in verbose mode. If we are , then we log a stack trace in addition to an error message
  public static boolean isVerbose() {
    return verbose;
  }
}
