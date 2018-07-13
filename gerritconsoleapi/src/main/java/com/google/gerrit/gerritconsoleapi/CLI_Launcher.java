package com.google.gerrit.gerritconsoleapi;

public class CLI_Launcher {

  public static void main(final String[] arguments){
    final GerritCLIArgParse instance = new GerritCLIArgParse();
    try{
      instance.doMain(arguments);
      System.exit(0);
    }
    catch (Exception ex){
      System.out.println("ERROR: Exception encountered: " + ex);
      ex.printStackTrace();
    }
  }
}
