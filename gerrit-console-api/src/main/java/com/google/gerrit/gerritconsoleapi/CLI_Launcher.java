
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

import com.google.gerrit.gerritconsoleapi.cli.commands.MainProgramCommand;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import static  com.google.gerrit.gerritconsoleapi.GerConError.GENERAL_RUNTIME_ERROR;
import static  com.google.gerrit.gerritconsoleapi.GerConError.COMMAND_SUCCESSFUL;
import static  com.google.gerrit.gerritconsoleapi.GerConError.DEFAULT_ERROR;

public class CLI_Launcher {

  private static Logger logger = LoggerFactory.getLogger(CLI_Launcher.class);

  public static void main(final String[] arguments)  {

    // Order of precedence for command args:
    // 1. Command-line args ( --verbose )
    // 2. Java system property log4j.configuration, this is automatically read in
    //    by slf4j logger that is initialised at start of this class
    // 3. Built-In jar defaults ( log4j.properties  )

    // Check if we have to run in verbose mode.
    // Verbose mode sets debug log level and logs stack traces in addition to
    // error messages in exception handling blocks
    List<String> args = Arrays.asList(arguments);
    if (args.contains("--verbose")) {
      Logging.setVerbose();
      logger.warn("Verbose mode enabled, overriding log4j.configuration system property");
    }

    // Execute the command
    final MainProgramCommand instance = new MainProgramCommand();
    try {
      instance.doMain(arguments);
    } catch(LogAndExitException ex1) {
      // console-api internal exceptions
      Logging.logerror(logger,"Gerrit Error: " + ex1.getMessage(), ex1);
      System.exit(ex1.getExitCode());
    } catch(RuntimeException ex2) {
      // java runtime exceptions
      Logging.logerror(logger,"Runtime Error: " + ex2.getMessage(), ex2);
      System.exit(GENERAL_RUNTIME_ERROR.getCode());
    } catch(Exception ex3){
      // general exceptions not handled by above
      Logging.logerror(logger,"General Error:" + ex3.getMessage(), ex3);
      System.exit(DEFAULT_ERROR.getCode());
    }

    // console-api command successful
    System.exit(COMMAND_SUCCESSFUL.getCode());
  }

}
