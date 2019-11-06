
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

import com.google.common.base.Strings;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;

import com.wandisco.gerrit.gitms.shared.config.lfs.LfsConfigFactory;
import com.wandisco.gerrit.gitms.shared.config.lfs.LfsProjectConfigSection;
import com.wandisco.gerrit.gitms.shared.config.lfs.LfsStorageBackend;
import com.wandisco.gerrit.gitms.shared.lfs.LfsFsRepository;
import com.wandisco.gerrit.gitms.shared.lfs.LfsFsRepositoryFactory;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import org.eclipse.jgit.api.errors.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class LfsRepositoryUtilities extends Logging {

  /**
   * Return the LfsStorageBackend object for a given backend name.
   * @param backendName
   * @return
   * @throws LogAndExitException
   */
  public static LfsStorageBackend getBackendByNamespace(String backendName, LfsConfigFactory configFactory) throws LogAndExitException {

    if (configFactory == null) {
      // get the default instance for them.
      try {
        configFactory = LfsConfigFactory.getInstance();
      } catch (Exception e) {
        e.printStackTrace();
        throw new LogAndExitException("Failed to otain the LfsConfigFactory instance. ", e);
      }
    }

    try {
      Map<String, LfsStorageBackend> backends = configFactory.getGerritServerLfsConfig().getLfsStorageBackends();

      // Get the backend from this name.
      if (backendName.equals(LfsStorageBackend.DEFAULT)) {
        // ok this repo is using the default backend, use its location.
        return configFactory.getGerritServerLfsConfig().getDefaultLfsStorageBackend();
      } else if (backends.containsKey(backendName)) {
        // we are using a custom named backend, lets try to find its storage location.
        return backends.get(backendName);
      }

      // throw as its not valid for some reason.
      throw new LogAndExitException(
          String.format("Unable to find backend namespace {%s} in gerrit_root/etc/LFS.config.", backendName));

    } catch (Exception e) {
      throw new LogAndExitException(
          String.format("Unable to find backend namespace {%s} in gerrit_root/etc/LFS.config.", backendName), e);
    }
  }


  /**
   * Validate that a repository is real and exists on disk, on this server in the gerrit repo home.
   *
   * We support the repo name with or without the .git suffix.
   * @throws LogAndExitException
   */
  public static Path validateRepositoryIsReal(GitMsApplicationProperties gitMsApplicationProperties, String repositoryName ) throws LogAndExitException {

    GitMsApplicationProperties applicationProperties = null;
    try {
      applicationProperties = gitMsApplicationProperties == null ? new GitMsApplicationProperties() : gitMsApplicationProperties;
    } catch (IOException e) {
      throw new LogAndExitException("Failed to get GitMS application properties. Details: ", e);
    }

    String repoHome = null;
    try {
      if (Strings.isNullOrEmpty(applicationProperties.getGerritRepoHome()))
      {
        throw new LogAndExitException("Invalid null value for {gerrit.repo.home}.");
      }
      repoHome = applicationProperties.getGerritRepoHome();
    } catch (IOException e) {
      throw new LogAndExitException("A problem occurred when obtaining gitms property {gerrit.repo.home}. Details: ", e);
    }

    // Search for our repo in the gerrit repo home location.
    Path repoPath = Paths.get(repoHome, repositoryName);
    File repositoryLocation = repoPath.toFile();

    String tmpRepositoryName;

    if ( !repositoryLocation.exists())
    {
      // Just hold on, before we give up add .git to the end of the name if its not there, or vice versa try without it.
      if ( repositoryName.endsWith(".git") )
      {
        tmpRepositoryName =stripGitSuffix(repositoryName);
      }
      else{
        tmpRepositoryName = appendGitSuffix(repositoryName);
      }

      // lets try again
      repoPath = Paths.get(repoHome, tmpRepositoryName);
      repositoryLocation = repoPath.toFile();
      if ( !repositoryLocation.exists()) {
        // doesn't exist?
        throw new LogAndExitException(
            String.format("The repository specified {%s} does not exist at the gerrit.repo.home location: {%s}",
                repositoryName, repoHome));
      }

    }

    if (!repositoryLocation.canRead())
    {
      throw new LogAndExitException(
          String.format("The repository specified {%s} cannot be read at location: {%s}",
              repositoryName, repositoryLocation.getPath()));
    }

    return repoPath;
  }
  /**
   * Creates a map of name / value pairs which are the repository LFS configuration.
   * @param reposLfsConfiguration
   * @return
   */
  public static Map<String, String> getConfigurationMapOfValues(LfsProjectConfigSection reposLfsConfiguration) {
    Map<String, String> lfsconfiginfo = new HashMap<String, String>();

    lfsconfiginfo.put("namespace", reposLfsConfiguration.getNamespace());
    lfsconfiginfo.put("backend", reposLfsConfiguration.getBackend() == null ? "default" : reposLfsConfiguration.getBackend());
    lfsconfiginfo.put("maxObjectSize", Long.toString(reposLfsConfiguration.getMaxObjectSize()));
    lfsconfiginfo.put("enabled", Boolean.toString(reposLfsConfiguration.isEnabled()));
    lfsconfiginfo.put("readOnly", Boolean.toString(reposLfsConfiguration.isReadOnly()));
    return lfsconfiginfo;
  }

  public static Path getLFSRepoStorageLocation(String repositoryName, String backendName, Path gerritRootDir, Path lfsDefaultDataDirectory) throws LogAndExitException {

    /*
     * Parse the lfs.config for the backend information
     */
    LfsFsRepository lfsRepo;
    try {
      lfsRepo = LfsFsRepositoryFactory.get(repositoryName, backendName, gerritRootDir, lfsDefaultDataDirectory);
    } catch (InvalidConfigurationException | IOException ex) {
      throw new LogAndExitException(
          String.format("Unable to gain LFS information about repository: {%s} using backend: {%s}", repositoryName, backendName), ex);
    } catch (Exception ex) {
      throw new LogAndExitException(
          String.format("Unable to gain LFS information about repository: {%s} using backend: {%s}", repositoryName, backendName), ex);

    }

    /*
     * Get the file store location from the LfsFsRepository object.
     */
    if (lfsRepo.getLocalFileStore() == null || lfsRepo.getLocalFileStore().toFile() == null) {
      throw new LogAndExitException(
          String.format("Unable to determine the location of the LFS file store for repository: {%s} using backend: {%s}", repositoryName, backendName));
    }

    return lfsRepo.getLocalFileStore();
  }

  public static String appendGitSuffix(String name){
    // if its already ends with .git, drop it, or slash drop it, so we can safely add the .git suffix.
    String tmpName = stripGitSuffix(name);

    return tmpName + ".git";
  }

  public static String stripGitSuffix(String name) {
    if (name.endsWith(".git")) {
      // Be nice and drop the trailing ".git" suffix, which we never keep
      // in our database, but clients might mistakenly provide anyway.
      //
      name = name.substring(0, name.length() - 4);
      while (name.endsWith("/")) {
        name = name.substring(0, name.length() - 1);
      }
    }
    return name;
  }



}
