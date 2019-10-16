
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

public class CLI_Launcher {

  public static void main(final String[] arguments){
    final MainProgramCommand instance = new MainProgramCommand();
    try{
      instance.doMain(arguments);
    }
    catch (Exception ex){
      // default output to the console, and stack trace to the error stream which may be rooted differently.
      System.out.println("Unexpected error occurred, Error Details: " + ex.getMessage());
      ex.printStackTrace();

      // TODO Possibly add different exit codes if we need them in the calling apps.
      System.exit(1);
    }
  }


}
