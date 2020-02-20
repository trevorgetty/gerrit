
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
import static  com.google.gerrit.gerritconsoleapi.GerConError.GENERAL_RUNTIME_ERROR;
import static  com.google.gerrit.gerritconsoleapi.GerConError.DEFAULT_ERROR;

public class CLI_Launcher {

  public static void main(final String[] arguments) {
    final MainProgramCommand instance = new MainProgramCommand();
    try {
      instance.doMain(arguments);
    } catch(LogAndExitException ex1) {
      System.err.println("Gerrit Error : " + ex1.getMessage());
      ex1.printStackTrace();
      System.exit(ex1.getExitCode());
    } catch(RuntimeException ex2) {
      System.err.println("General Error : " + ex2.getMessage());
      ex2.printStackTrace();
      System.exit(GENERAL_RUNTIME_ERROR.getCode());
    } catch(Exception ex3){
      // default output to the console, and stack trace to the error stream which may be rooted differently.
      System.err.println("Unexpected Error : " + ex3.getMessage());
      ex3.printStackTrace();
      System.exit(DEFAULT_ERROR.getCode());
    }
  }

}
