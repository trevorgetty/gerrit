package com.google.gerrit.gerritconsoleapi;

public class CLI_Launcher {

  public static void main(final String[] arguments){
    final GerritCLIArgParse instance = new GerritCLIArgParse();
    try{
      instance.doMain(arguments);
    }
    catch (Exception ex){
      System.out.println("ERROR: " + ex);
      ex.printStackTrace();
    }
  }
}
