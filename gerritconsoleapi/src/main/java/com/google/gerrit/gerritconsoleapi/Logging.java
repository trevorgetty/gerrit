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
    System.out.println("consoleapi: INFO: " + (s == null ? "null" : s));
    logger.info(s);
  }

  public static void logwarning(String s)
  {
    System.err.println("consoleapi: WARNING: " + (s == null ? "null" : s));
    logger.warn(s);
  }
}
