
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
import com.google.gerrit.gerritconsoleapi.cli.processing.CliCommandItemBase;
import com.google.gerrit.gerritconsoleapi.cli.processing.CmdLineParserFactory;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.gerrit.sshd.CommandMetaData;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static com.google.gerrit.gerritconsoleapi.GerConError.LFS_RUNTIME_ERROR;

@CommandMetaData(name = "help", description = "Application help")
public class HelpCommand extends CliCommandItemBase {

  private static Logger logger = LoggerFactory.getLogger(HelpCommand.class);

  @Argument(required =false, index = 0, metaVar = "<command>", usage = "Use: 'java -jar console-api.jar help <command>'.\n")
  public String helpOnCommand;

  public HelpCommand(){
    super( "help");
  }

  @Override
  public void execute() throws LogAndExitException {
    if (Strings.isNullOrEmpty(helpOnCommand)) {
      // if we have no argument subcommand, then we should output all the help context.
      new MainProgramCommand().displayHelp();

      return;
    }

    // Workaround!
    // This is because args4j does not display subcommand useage /examples
    // at the moment. see https://github.com/kohsuke/args4j/issues/106
    logger.debug(String.format("Help on command: %s", helpOnCommand));

    // get the appropriate command class now and call.
    // TODO Find some way of looking up the command name, and obtaining the command class from annotations
    // so this works for new commands.
    switch (helpOnCommand.toLowerCase()) {
      case "lfs-info":
        System.out.println(helpOnCommand + " command:");
        new LfsInformationCommand().displayHelp(true);
        break;
      case "lfs-content":
        System.out.println(helpOnCommand + " command:");
        new LfsContentCommand().displayHelp(true);
        break;
      case "config":
        System.out.println(helpOnCommand + " command:");
        new ConfigurationCommand().displayHelp(true);
        break;
      case "help":
        System.out.println(helpOnCommand + " command:");
        displayHelp();
        break;
      default:
        throw new LogAndExitException(LFS_RUNTIME_ERROR.getDescription() + String.format(" : Unknown help command: {%s} specified.", helpOnCommand), LFS_RUNTIME_ERROR.getCode());
    }
  }

  @Override
  public void execute(String... arguments) throws LogAndExitException {
     // both the execute and display help should do the same thing for the help command!
     execute();
  }

  @Override
  public void displayHelp() {
    // Take a newline, and display the help information, and example use.
    System.out.println("");

    System.out.println("**********************************");
    System.out.println("  Gerrit command line Api - Help. ");
    System.out.println("**********************************");

    // print example use, of just required props for each available command.
    System.out.println("");


    // print example use, of just required props for each available subcommand, to do this
    // output the main help for the top level argument
    CmdLineParser localCmdLineParser = CmdLineParserFactory.createCmdLineParser(new MainProgramCommand());
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

      System.err.println(
          String.format("Command Example - %s\n"
                  + "    console-api %s %s",
              commandItem.getCommandName(), commandItem.getCommandName(), subCommandParser.printExample(OptionHandlerFilter.REQUIRED)));
      System.err.println("");
    }

  }
}
