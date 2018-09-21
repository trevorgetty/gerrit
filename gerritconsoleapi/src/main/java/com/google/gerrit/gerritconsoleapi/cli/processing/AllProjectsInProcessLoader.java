
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

import com.google.common.base.Strings;
import com.google.gerrit.gerritconsoleapi.AllProjectsCommands;
import com.google.gerrit.gerritconsoleapi.bindings.GuiceConfigurator;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectLevelConfigNoCache;
import com.google.gerrit.gerritconsoleapi.cli.commands.ConfigurationCommand;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.config.lfs.AllProjectsLoaderCallback;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ReplicationUtils;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.wandisco.gerrit.gitms.shared.util.ReplicationUtils.getGitConfigLocationProperty;

/**
 * An AllProjects callback implementation which allows for the AllProjects information to be loaded in the same
 * process as we are already in ( console-api.jar ) without having to start another instance.
 */
public class AllProjectsInProcessLoader implements AllProjectsLoaderCallback {

  private static final Logger logger = LoggerFactory.getLogger(AllProjectsInProcessLoader.class);

  private LocalGuiceContextLoader contextLoader;

  // Git configuration location override only -> not required!
  private String gitConfigLocationOverride;

  @Override
  public String getGitConfigLocationOverride() {
    return gitConfigLocationOverride;
  }

  @Override
  public void setGitConfigLocationOverride(String gitConfigLocationOverride) {
    this.gitConfigLocationOverride = gitConfigLocationOverride;
  }

  public AllProjectsInProcessLoader( final String gitConfigLocationOverride )
  {
    this.gitConfigLocationOverride = gitConfigLocationOverride;
  }

  @Override
  public Config getAllProjectsConfiguration(String configFilename) throws Exception {

    if ( contextLoader == null )
    {
      contextLoader = new LocalGuiceContextLoader(gitConfigLocationOverride);
    }


    AllProjectsCommands allProjectsCommands = new AllProjectsCommands(contextLoader.getGuiceContext(), configFilename);
    ProjectLevelConfigNoCache allProjectConfig = allProjectsCommands.getProjectConfig();
    return allProjectConfig.get();
  }
}
