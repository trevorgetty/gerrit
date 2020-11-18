
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
import com.google.gerrit.gerritconsoleapi.Logging;
import com.google.gerrit.gerritconsoleapi.bindings.GuiceConfigurator;
import com.google.gerrit.gerritconsoleapi.cli.commands.LfsInformationCommand;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.exception.ConfigurationException;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ReplicationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.wandisco.gerrit.gitms.shared.util.ReplicationUtils.getGitConfigLocationProperty;
import static com.google.gerrit.gerritconsoleapi.GerConError.LFS_CONFIG_INFO_ERROR;

public class LocalGuiceContextLoader {

  private static Logger logger = LoggerFactory.getLogger(LocalGuiceContextLoader.class);

  private Injector programGuiceContext;

  // Git configuration location override only -> optional field!
  private String gitConfigLocationOverride;

  public String getGitConfigLocationOverride() {
    return gitConfigLocationOverride;
  }

  public void setGitConfigLocationOverride(String gitConfigLocationOverride) {
    this.gitConfigLocationOverride = gitConfigLocationOverride;
  }

  public Injector getGuiceContext() throws LogAndExitException {
    // if we already have a context loaded, just return it.
    if ( programGuiceContext != null )
    {
      return programGuiceContext;
    }

    return loadGuiceContext();
  }

  public LocalGuiceContextLoader( String gitConfigLocationOverride ){
    this.gitConfigLocationOverride = gitConfigLocationOverride;
  }

  /**
   * Load the main guice context, with lcoal bindings based on whatever gitconfig we have been given directly
   * or from the environment
   */
  private Injector loadGuiceContext() throws LogAndExitException {

    /*
     * If the gitconfig arg is empty, try using our standard args to get it from environment.
     */
    if ( Strings.isNullOrEmpty(gitConfigLocationOverride) ){
      gitConfigLocationOverride = getGitConfigLocationProperty();
    }

    /*
     The gitConfigLocationOverride cannot be empty by this stage. If it is throw an exception.
     */
    if (gitConfigLocationOverride.isEmpty()) {
      Logging.logerror(logger, "Invalid git configuration args.", null);
      throw new LogAndExitException(LFS_CONFIG_INFO_ERROR.getDescription() + " : The \"git-config\" must either be specified manually or found via GIT_CONFIG java property, system environment or ${users.home}/.gitconfig file.", LFS_CONFIG_INFO_ERROR.getCode());
    }

  /*
   The full path to the .gitconfig file must be specified
   */
    File gitconfigFile = new File(gitConfigLocationOverride);
    if (!gitconfigFile.exists() || gitconfigFile.isDirectory()) {
      Logging.logerror(logger, String.format("git configuration %s does not exist or is directory", gitConfigLocationOverride), null);
      throw new LogAndExitException(LFS_CONFIG_INFO_ERROR.getDescription() + " : The \".gitconfig\" file provided does not exist or is directory. Please supply the full path to this file.", LFS_CONFIG_INFO_ERROR.getCode());
    }

    /*
     * To enforce that the rest of the application now uses this gitconfig environment, regardless of where it came from.
     * Set the full path to the .gitconfig file as a system property
     */
    System.setProperty("GIT_CONFIG", gitConfigLocationOverride);


    /*
     Calling into gerrit.gitms.shared library to parse the GitMS application.properties
     */
    GitMsApplicationProperties confProps;
    try {
      confProps = ReplicationUtils.parseGitMSConfig();
    } catch (final IOException | ConfigurationException e) {
      throw new LogAndExitException(LFS_CONFIG_INFO_ERROR.getDescription() + " : Problem occurred when retrieving GitMS configuration. Error Details: ", e, LFS_CONFIG_INFO_ERROR.getCode());
    }

    /*
     The sitePath will be the gerrit.root declared within the application.properties.
     With the sitePath declared, the Guice bindings to the application classes can be performed.
     */
    final Path gerritSitePath = Paths.get(confProps.getGerritRoot());

    GuiceConfigurator configurator = new GuiceConfigurator(gerritSitePath);
    programGuiceContext = configurator.getMainInjector();

    return programGuiceContext;
  }

}
