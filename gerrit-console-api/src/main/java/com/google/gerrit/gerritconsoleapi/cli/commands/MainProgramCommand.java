
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
 
package com.google.gerrit.gerritconsoleapi.cli.commands;


import com.google.common.base.Strings;
import com.google.gerrit.gerritconsoleapi.Logging;
import com.google.gerrit.gerritconsoleapi.cli.processing.CliCommandItemBase;
import com.google.gerrit.gerritconsoleapi.cli.processing.CmdLineParserFactory;
import com.google.gerrit.gerritconsoleapi.cli.processing.CommandItem;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import org.kohsuke.args4j.*;

import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommandHandler;
import org.kohsuke.args4j.spi.SubCommands;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.google.gerrit.gerritconsoleapi.GerConError.LFS_RUNTIME_ERROR;

/**
 * A class used to parse configuration files stored in the All-Projects repo.
 * Command line arguments can be passed which are used to specify the config
 * in which to parse.
 *
 * Use the Args4j annotations below, to control the default command, any alias the command may
 * have and if possible put in example usage.
 *
 * We by default call the PrintUsage method to show help information if the user gets the args wrong
 * or they use the --help option directly.
 *
 * @author trevorgetty
 * @author RonanConway
 */
public class MainProgramCommand implements CommandItem {

  private static final Logger logger = LoggerFactory.getLogger(MainProgramCommand.class);


  // For backward compatibility we dont require an arg to be present, but if it is, it has to be the first command, just like
  // git commands, e.g. git push xxx, console-api.jar help ...
  @Argument(index = 0, metaVar = "command", usage = "Use: 'java -jar console-api.jar <command> [args]'\n" +
      "Commands list: \n" +
      "      config\n" +
      "      lfs-info\n" +
      "      lfs-content\n" +
      "      help\n",
      handler = SubCommandHandler.class)
  @SubCommands({
      @SubCommand(name = "config", impl = ConfigurationCommand.class),
      @SubCommand(name = "lfs-info", impl = LfsInformationCommand.class),
      @SubCommand(name = "lfs-content", impl = LfsContentCommand.class),
      @SubCommand(name = "help", impl = HelpCommand.class)
  })
  public CommandItem commandItem;

  @Option(name = "--verbose", hidden = true, required=false, usage = "(Optional) Enable debug and stacktrace output to STDERR")
  private boolean verbose;

  /**
   * Parses the arguments passed in from the command line of the application
   * and returns a Config object to the caller. The GuiceConfigurator is called
   * to setup the necessary bindings and configuration for the tool to interact
   * with gerrit.
   *
   * @param arguments
   * @return
   * @throws Exception
   * @throws IOException
   */
  public void doMain(final String... arguments) throws Exception, IOException {

    logger.trace("Executing Command");

    parseAndRunAppropriateCommand(this, arguments);

    logger.trace("Command finished");
  }


  /**
   * Helper method to take a command item bean, and parse the existing arguments to create that command.
   * It will then try to invoke its main execute function, or displayHelp if help was requested.
   *
   * @param commandTypeBean
   * @param arguments
   */
  protected static void parseAndRunAppropriateCommand(CommandItem commandTypeBean, final String... arguments) throws LogAndExitException {
    CmdLineParser parser = CmdLineParserFactory.createCmdLineParser(commandTypeBean);

    try {
      // we want to create a list of all the options that remain, and leave onto a single argument for this main
      // class, if we can't do this, just allow the default handling to kick in using the default SubCommand->Config
      parser.parseArgument(arguments);
    } catch (CmdLineException ex) {
      // Write error to STDERR
      Logging.logerror(logger, "console-api: ERROR: " + ex.getMessage(), ex);

      // Sneak a peak at which command we are running, if its the main program and an error ocurred,
      // it could be cause it was trying to create a subcommmand and failed, use its error information, as it is much
      // better to show help for a failed command that general help.
      CommandItem cmdItemBean = getCommandContextForHelp(commandTypeBean, arguments);
      cmdItemBean.displayHelp();

      // Write command usage information to STDOUT
      System.err.println("\nPlease use 'console-api help <command>' for more specific context information.\n");
      return;
    }

    // execute this main command
    commandTypeBean.execute(arguments);
  }

  private static CommandItem getCommandContextForHelp(CommandItem commandTypeBean, String[] arguments) {
    if (commandTypeBean instanceof MainProgramCommand) {

      if (arguments.length < 1) {
        return commandTypeBean;
      }

      String arg = arguments[0];
      if (Strings.isNullOrEmpty(arg) || arg.startsWith("-")) {
        // its empty, or an OPTION, so just return as normal
        return commandTypeBean;
      }

      // its not an option so take it as the arg we want ( position 0 ).
      // again a nice reflection based parse through this list would be great but this will do for now
      List<CliCommandItemBase> commandItemArrayList = Arrays.asList(
          new ConfigurationCommand(),
          new LfsContentCommand(),
          new LfsInformationCommand(),
          new HelpCommand());

      for (CliCommandItemBase commandItem : commandItemArrayList) {
        if (commandItem.getCommandName().equals(arg)) {
          // we have a match to the arg 0.  Use this as the command context to parse help within.
          // it is a subcommand then of some type and not the main command!
          return commandItem;
        }
      }
    }

    return commandTypeBean;
  }

  /**
   * Execute this classes purpose, which is to call the appropriate sub command now we have got this far.
   */
  @Override
  public void execute(final String... arguments) throws LogAndExitException {

    if (commandItem == null) {
      // no value has been given for argument, so just use default of "config" which it used to be
      // before I added argument support, and re-parse the args as if it was the mainProgram.
      parseAndRunAppropriateCommand(new ConfigurationCommand(), arguments);
      return;
    }

    // run whatever sub command item we got setup via the command parsing.
    commandItem.execute();
  }

  @Override
  public void execute() throws LogAndExitException {
    throw new LogAndExitException(LFS_RUNTIME_ERROR.getDescription() + " : Not supported to call execute without args here.", LFS_RUNTIME_ERROR.getCode());
  }
  /**
   * Display Help for this application, and example use.
   */
  @Override
  public void displayHelp() {

    // Take a newline, and display the help information, and example use.
    System.out.println("");

    System.out.println("**********************************");
    System.out.println("  Gerrit command line Api Help    ");
    System.out.println("**********************************");

    // print the list of available options
    CmdLineParser localCmdLineParser = CmdLineParserFactory.createCmdLineParser(this);
    localCmdLineParser.printUsage(System.out);

    // TODO, make this easier, to get any command in namespace X?  maybe reflection or bindings?
    // Show each command here.

      List<CliCommandItemBase> commandItemArrayList = Arrays.asList(
          new ConfigurationCommand(),
          new LfsContentCommand(),
          new LfsInformationCommand(),
          new HelpCommand());

      for (CliCommandItemBase commandItem : commandItemArrayList) {
        CmdLineParser subCommandParser = new CmdLineParser(commandItem);

        System.out.println(
            String.format("Command Example - %s\n"
                    + "    java -jar console-api.jar %s %s",
                commandItem.getCommandName(), commandItem.getCommandName(), subCommandParser.printExample(OptionHandlerFilter.REQUIRED)));
        System.out.println("");
      }

  }

}
