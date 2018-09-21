
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
 
package com.google.gerrit.gerritconsoleapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for logging output using by the consoleapi jar.
 */
public abstract class Logging {

  private static Logger logger = LoggerFactory.getLogger(Logging.class);

  public static void loginfo(String s)
  {
    System.out.println("console-api: INFO: " + (s == null ? "null" : s));
    logger.info(s);
  }

  public static void logwarning(String s)
  {
    System.err.println("console-api: WARNING: " + (s == null ? "null" : s));
    logger.warn(s);
  }
}
