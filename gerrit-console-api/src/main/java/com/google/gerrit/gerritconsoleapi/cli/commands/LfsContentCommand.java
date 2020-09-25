
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
import com.google.gerrit.gerritconsoleapi.cli.processing.AllProjectsInProcessLoader;
import com.google.gerrit.gerritconsoleapi.cli.processing.CliCommandItemBase;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.gerrit.sshd.CommandMetaData;
import com.wandisco.gerrit.gitms.shared.config.lfs.LfsConfigFactory;
import com.wandisco.gerrit.gitms.shared.config.lfs.LfsProjectConfigSection;
import com.wandisco.gerrit.gitms.shared.exception.ConfigurationException;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


import static com.google.gerrit.gerritconsoleapi.GerConError.LFS_CONTENT_ERROR;
import static com.google.gerrit.gerritconsoleapi.LfsRepositoryUtilities.*;
import static com.wandisco.gerrit.gitms.shared.commands.GitCommandRunner.lfsLsFiles;

@CommandMetaData(name = "lfs-content", description = "Lfs content filepaths for belonging to the specified repo")
public class LfsContentCommand extends CliCommandItemBase {

  private static Logger logger = LoggerFactory.getLogger(LfsContentCommand.class);

  @Option(name = "--repositoryname", aliases = "-r", usage = "The repository name to gather lfs content paths from.", metaVar = "lfstest01.git", required = true)
  private String repositoryName;

  @Option(name = "--outputfile", aliases = "-o", usage = "The lfs contents information is written to this file. ", metaVar = "lfs-content-list.out")
  private String outputfile;

  // We now default git config location using the standard rules employed by the installer scripts which is environment $GIT_CONFIG, or user.home/.gitconfig
  // if you specify this arg it will overrule these.
  @Option(name = "--git-config", aliases = "-g", usage = "The location of the .gitconfig configuration file.", metaVar = "~/.gitconfig or /opt/wandisco/gitms/.gitconfig", required = false)
  private String gitConfigArg;

  private LfsConfigFactory configFactory;
  private final GitMsApplicationProperties applicationProperties;

  public LfsContentCommand(){
    super( "lfs-content");

    try {
      applicationProperties = new GitMsApplicationProperties();
    } catch (final IOException | ConfigurationException e) {
      Logging.logerror(logger, "console-api: ERROR: " + e.getMessage(), e);
      // Throw exception to write out additional stack trace if --verbose enabled , and exit cons
      throw new LogAndExitException(LFS_CONTENT_ERROR.getDescription() + " : Unable to obtain LFS configuration information.", e, LFS_CONTENT_ERROR.getCode());
    }

    try {
      configFactory = LfsConfigFactory.getInstance(applicationProperties.getGerritRoot());
      AllProjectsInProcessLoader allProjectsLoader = new AllProjectsInProcessLoader(gitConfigArg);

      configFactory.setAllProjectsLoaderCallback(allProjectsLoader);
    } catch (Exception e) {
      Logging.logerror(logger, "console-api: ERROR: " + e.getMessage(), e);
      // Throw exception to write out additional stack trace if --verbose enabled , and exit cons
      throw new LogAndExitException(LFS_CONTENT_ERROR.getDescription() + " : Unable to obtain LFS configuration information.", e, LFS_CONTENT_ERROR.getCode());
    }
  }

  @Override
  public void execute() throws LogAndExitException {

    logger.trace("Starting execution.");
    logger.debug(String.format("Using repository: {%s} and outputFile: {%s}", repositoryName, outputfile));

    // Now we have 2 typos of use of the reponame, 1) for lfs checking, it uses the projectname without the .git
    // suffix.  But for finding it on disk, it might need the .git suffix on the end.
    if ( repositoryName.endsWith(".git")) {
      repositoryName = stripGitSuffix(repositoryName);
    }
    
    // check the repo is valid -> and can be found in gerrit_repo_home.
    Path repositoryPath = validateRepositoryIsReal(applicationProperties, repositoryName);

    // Now validate its an LFS type repo.
    try {
      if ( !configFactory.getLfsAllProjectsConfig().checkIfProjectIsLfs(repositoryName) )
      {
        // this project isn't recognised as LFS.
        logger.warn(String.format("The repository given: {%s} is not recognised as a valid LFS repository. Please check the repository name, or validate the repository status, in the UI", repositoryName));
        return;
      }
    } catch (Exception e) {
      throw new LogAndExitException( LFS_CONTENT_ERROR.getDescription() + " : Unable to obtain information about whether this repository is LFS enabled. Details: ", e, LFS_CONTENT_ERROR.getCode());
    }

    // it is a repo, get its LFS Project configuration.
    LfsProjectConfigSection reposLfsConfiguration = null;
    try {
      reposLfsConfiguration = configFactory.getLfsAllProjectsConfig().getSpecificProjectsLfsConfig(repositoryName);
    } catch (Exception e) {
      throw new LogAndExitException(LFS_CONTENT_ERROR.getDescription() + " : Unable to obtain information repository LFS configuration information. Details: ", e, LFS_CONTENT_ERROR.getCode());
    }

    // Turn into a set of name / value pairs, representing the LFS configuration for this project for ease of use later.
    // Map<String, String> lfsconfiginfo = getConfigurationMapOfValues(reposLfsConfiguration);

    Path lfsStorageLocation;
    try {
      lfsStorageLocation = getLFSRepoStorageLocation( repositoryName,
          reposLfsConfiguration.getBackend(),
          configFactory.getGerritServerLfsConfig().getGerritRootDir(),
          configFactory.getGerritServerLfsConfig().getDefaultBackendDirectory());
    } catch (Exception e) {
      throw new LogAndExitException(LFS_CONTENT_ERROR.getDescription() + " : Unable to obtain LFS backend storage location. Details: ", e, LFS_CONTENT_ERROR.getCode());
    }

    // ok now we have the storage location on disk lets get the full list of content from this repo.
    // Make sure we use the "git lfs ls-files with --long and --all to get all content across all branches
    List<String> lfsContentOids;
    try {
      List<String> lfsContentInfo = lfsLsFiles( repositoryPath.toFile(), "--all");

      // we need to change the information returned from being of format
      // <oid> - <filename> to only being a list of <oids>
      lfsContentOids = parseLfsContentInfo(lfsContentInfo);
    } catch (Exception e) {
      throw new LogAndExitException(String.format(LFS_CONTENT_ERROR.getDescription() + " : Failed to obtain the list of lfs-content : " , e, LFS_CONTENT_ERROR.getCode()));
    }

    // ok now we have a list of content, we need to do 2 things.
    // 1) Create full paths to these content files.
    // 2) Create a file on disk in the output location with this information.
    outputLfsContent( lfsContentOids, lfsStorageLocation);
    logger.trace("Finished execution.");
  }

  /**
   * Parse the lfs lfs-files command output, to leave only the <oids> list.
   * @param lfsContentInfo
   * @return
   */
  private List<String> parseLfsContentInfo(List<String> lfsContentInfo) throws LogAndExitException {

    List<String> oidListOnly = new ArrayList<>(lfsContentInfo.size());
    // The first string is the long format oid - sha.
    for ( String lfsContent : lfsContentInfo)
    {
      String [] items = lfsContent.split(" ");

      // item 0 should be our oid, check it has exactly 64 characters.
      String oid = items[0];

      if ( Strings.isNullOrEmpty(oid))
      {
        throw new LogAndExitException(
            String.format( LFS_CONTENT_ERROR.getDescription() + " : A problem occurred processing the lfs-content as the lfs ls-files command returned an oid of incorrect length or format.\n" +
            "Item is: %s", lfsContent), LFS_CONTENT_ERROR.getCode());
      }

      // now check out item is 64 characters.
      if ( oid.length() != 64 )
      {
        throw new LogAndExitException(
            String.format(LFS_CONTENT_ERROR.getDescription() + " : A problem occurred processing the lfs-content as the lfs ls-files command returned a list which doesn't have an 64 character oid as first member.\n" +
                "Item is: {%s} with first string being of length: {%s}", lfsContent, oid.length()), LFS_CONTENT_ERROR.getCode());
      }

      // now we have the oid add to our list.
      oidListOnly.add(oid);
    }

    return oidListOnly;
  }


  private void outputLfsContent( List<String> lfsContentOids, Path lfsStorageLocation) throws LogAndExitException {

    String lfsStoragePath = lfsStorageLocation.toFile().getPath();

    if ( outputfile != null ) {
      try (PrintWriter out
               = new PrintWriter(new BufferedWriter(new FileWriter(outputfile)))) {

        // create a filewriter on disk, overwrite whatever is already there as this allows multiple runs from same
        // location for different repos, without user having to tidy up.  But you can't run at same time, if you do,
        // you MUST pass different locations.
        // Note create a full bufferedwriter, as we want performant writing to disk as there could be a good bit of data,
        // we dont want each write to force bytes to actually get written to disk.
        for (String lfsOid : lfsContentOids) {
          // build up the full path to this item.
          String fullLfsContentLocation = getLfsContentFullPath(lfsOid, lfsStoragePath);

          out.println(fullLfsContentLocation);
        }

        System.out.println(String.format("Processed a total of:{%s} lfs content entries.", lfsContentOids.size()));

      } catch (IOException e) {
        throw new LogAndExitException(LFS_CONTENT_ERROR.getDescription() + " : A problem occurred when writing the lfs-content information to disk.", e, LFS_CONTENT_ERROR.getCode());
      }

      return;
    }

    // just output the information to the console directly.
    for (String lfsOid : lfsContentOids) {
      // build up the full path to this item.
      String fullLfsContentLocation = getLfsContentFullPath(lfsOid, lfsStoragePath);

      System.out.println(fullLfsContentLocation);
    }

  }

  /**
   * Utility method to build up a full path to where an LFS content file is,
   * Based on the lfs data storage location for a given repository backend, and the oid, we can work out
   * the path.
   * It is of the format <lfs_backend_storage>/12/34/123456782345
   * @param lfsOid
   * @param lfsStorageLocation
   * @return
   */
  private String getLfsContentFullPath(final String lfsOid, final String lfsStorageLocation ){

    return String.format("%s/%.2s/%.2s/%s", lfsStorageLocation, lfsOid, lfsOid.substring(2, 4), lfsOid);

  }

}
