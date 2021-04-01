
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
import com.google.gerrit.gerritconsoleapi.AllProjectsCommands;

import com.google.gerrit.gerritconsoleapi.cli.processing.CliCommandItemBase;
import com.google.gerrit.gerritconsoleapi.cli.processing.LocalGuiceContextLoader;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.gerrit.sshd.CommandMetaData;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CommandMetaData(name = "config", description = "Project configuration information")
public class ConfigurationCommand extends CliCommandItemBase {

  private static Logger logger = LoggerFactory.getLogger(ConfigurationCommand.class);

  private LocalGuiceContextLoader contextLoader;

  public ConfigurationCommand(){
    super( "config");
  }

  @Option(name = "--config-name", aliases = "-c", usage = "Name of the config file to return.", metaVar = "lfs.config", required = true)
  private String configName;

  @Option(name = "--get-sections", aliases = "-s", usage = "Display the config sections in the config file.", required = false)
  private boolean sections;

  @Option(name = "--get-sub-sections", aliases = "-ss", usage = "Give the section name to display subSections for.", metaVar = "lfs", required = false)
  private String subSectionArg;

  // OPTIONAL: We now default git config location using the standard rules employed by the installer scripts which is environment $GIT_CONFIG, or user.home/.gitconfig
  // if you specify this arg it will overrule these.
  @Option(name = "--git-config", aliases = "-g", usage = "The location of the .gitconfig configuration file.", metaVar = "~/.gitconfig or /opt/wandisco/gitms/.gitconfig", required = false)
  private String gitConfigArg;


  @Override
  public void execute() throws LogAndExitException {

    contextLoader = new LocalGuiceContextLoader(gitConfigArg);

    processAllProjectsConfig();

    logger.trace("Exiting application.");
  }


  /**
   * Using Guice to get an instance from the Injector for ProjectCache which
   * allows access to All-Projects configs
   */
  private void processAllProjectsConfig() throws LogAndExitException {

    AllProjectsCommands allProjectsCommands = new AllProjectsCommands(contextLoader.getGuiceContext(), configName);
    if (sections) {
      allProjectsCommands.displaySections();
    } else if (!Strings.isNullOrEmpty(subSectionArg)) {
      allProjectsCommands.displaySubSectionsForSection(subSectionArg);
    } else {
      allProjectsCommands.displayConfig();
    }
  }

}
