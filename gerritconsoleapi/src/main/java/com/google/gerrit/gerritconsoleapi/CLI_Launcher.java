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
    }
  }


}
