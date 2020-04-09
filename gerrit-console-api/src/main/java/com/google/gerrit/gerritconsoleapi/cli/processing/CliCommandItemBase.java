
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
 
package com.google.gerrit.gerritconsoleapi.cli.processing;

import com.google.gerrit.gerritconsoleapi.Logging;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import static com.google.gerrit.gerritconsoleapi.GerConError.LFS_RUNTIME_ERROR;

public abstract class CliCommandItemBase extends Logging implements CommandItem {

  private static Logger logger = LoggerFactory.getLogger(CliCommandItemBase.class);

  protected CmdLineParser parser;
  protected String commandName;

  protected CliCommandItemBase(String commandName) {
    this.commandName = commandName;
  }

  @Option(name = "--verbose", hidden = true, usage = "(Optional) Enable debug and stacktrace output to STDERR")
  private boolean verbose;

  public String getCommandName() {
    return commandName;
  }

  // add default implementation which say its not implemented yet of both executes.
  @Override
  public void execute() throws LogAndExitException {
    throw new LogAndExitException(LFS_RUNTIME_ERROR.getDescription() + " : This command doesn't support execution without args.", LFS_RUNTIME_ERROR.getCode());
  }

  @Override
  public void execute(String... arguments) throws LogAndExitException {
    throw new LogAndExitException(LFS_RUNTIME_ERROR.getDescription() + " : This command doesn't support execution without args.", LFS_RUNTIME_ERROR.getCode());
  }

  /**
   * Display Help for this application, and example use.
   */
  @Override
  public void displayHelp() {

    // request error help!
    displayHelp(false);
  }

  public void displayHelp(boolean requestHelp) {

    // Error help goes to error stream, requested help used output stream!
    PrintStream stream = requestHelp ? System.out : System.err;

    // Take a newline, and display the help information, and example use.
    stream.println("");

    stream.println("*********************************************************");
    stream.println("  Gerrit command line Api - " + getCommandName() + " Help.  ");
    stream.println("*********************************************************");

    // print the list of available options
    CmdLineParser localCmdLineParser = CmdLineParserFactory.createCmdLineParser(this);

    // Processing error, only show required, if its for requested help by the user, its an normal output, with all fields.
    if ( requestHelp )
    {
      // print all
      localCmdLineParser.printUsage(new OutputStreamWriter(stream), null, OptionHandlerFilter.PUBLIC);
    }
    else {
      localCmdLineParser.printUsage(new OutputStreamWriter(stream), null, OptionHandlerFilter.REQUIRED);
    }
  }

}
