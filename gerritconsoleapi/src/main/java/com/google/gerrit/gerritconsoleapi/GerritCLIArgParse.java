package com.google.gerrit.gerritconsoleapi;

import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ReplicationUtils;
import org.eclipse.jgit.lib.Config;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A class used to parse configuration files stored in the All-Projects repo.
 * Command line arguments can be passed which are used to specify the config
 * in which to parse. The default is to output the entire contents of the config
 * however there are flags which can be used to specify whether sections or subsections
 * are output instead.
 *
 * @author RonanConway
 */
public class GerritCLIArgParse {

  private CmdLineParser parser;
  private Injector injector;
  private Path sitePath;
  private GitMsApplicationProperties confProps;

  @Option(name="-c", aliases="--config-name", usage="Name of the config file to return", required=true)
  private String configName;

  @Option(name="-g", aliases="--git-config", usage="The location of the .gitconfig configuration file", required=true)
  private String gitConfigArg;

  @Option(name="-s", aliases="--get-sections", usage="Display the config sections in the config file", required=false)
  private boolean sections;

  @Option(name="-ss", aliases="--get-sub-sections", usage="Give the section name to display subSections for", required=false)
  private String subSectionArg;

  /**
   * Parses the arguments passed in from the command line of the application
   * and returns a Config object to the caller. The GuiceConfigurator is called
   * to setup the necessary bindings and configuration for the tool to interact
   * with gerrit.
   * @param arguments
   * @return
   * @throws Exception
   * @throws IOException
   */
  public void doMain(final String... arguments) throws Exception, IOException{

    parser = new CmdLineParser(this);
    try{
      parser.parseArgument(arguments);
    }
    catch (CmdLineException ex){
      System.out.println("ERROR: Unable to parse command-line options: " + ex);
    }

    /*
     The gitConfigArg cannot be empty. If it is throw an exception.
     */
    if(gitConfigArg == null || gitConfigArg.isEmpty()){
      //logger.debug("The gitConfigArg was set as " + gitConfigArg);
      throw new Exception("The \"--git-config\" argument must specify the full path to the \".gitconfig\" file ");
    }

    /*
     The full path to the .gitconfig file must be specified
     */
    File gitconfigFile = new File(gitConfigArg);
    if(!gitconfigFile.exists() || gitconfigFile.isDirectory()) {
      System.out.println("ERROR: The \".gitconfig\" file provided is invalid or a directory. " +
          "Please supply the full path to this file.");
    }

    /*
     Setting the full path to the .gitconfig file as a system property
     */
    System.setProperty("GIT_CONFIG", gitConfigArg);

    /*
     Calling into gerrit.gitms.shared library to parse the GitMS application.properties
     */
    confProps = ReplicationUtils.parseGitMSConfig();

    /*
     The sitePath will be the gerrit.root declared within the application.properties.
     With the sitePath declared, the Guice bindings to the application classes can be performed.
     */
    sitePath = Paths.get(this.extractSitePath());
    GuiceConfigurator configurator = new GuiceConfigurator(sitePath);
    injector = configurator.createSysInjector();

    processAllProjectsConfig();
  }

  /**
   * Using Guice to get an instance from the Injector for ProjectCache which
   * allows access to All-Projects configs
   * @return
   */
  private void processAllProjectsConfig() {
    ProjectCache projectCacheInstance = injector.getInstance(ProjectCache.class);
    if (sections) {
      displaySections(projectCacheInstance);
    } else if (!Strings.isNullOrEmpty(subSectionArg)){
      displaySubSectionsForSection(projectCacheInstance, subSectionArg);
    } else{
      displayConfig(projectCacheInstance);
    }
  }

  /**
   * Will dsiplay the entire contents of the specified config file.
   * @param projectCacheInstance
   */
  private void displayConfig(ProjectCache projectCacheInstance) {
    Config config = projectCacheInstance.getAllProjects().getConfig(this.configName).get();
    System.out.println(config.toText());
  }

  /**
   * display all the config sections
   * @param projectCacheInstance
   */
  private void displaySections(ProjectCache projectCacheInstance) {
    String config = projectCacheInstance.getAllProjects().getConfig(this.configName)
        .get().getSections().toString();
    System.out.println(config);
  }

  /**
   * display the subsections for a given section
   * @param projectCacheInstance
   */
  private void displaySubSectionsForSection(ProjectCache projectCacheInstance, String sectionName) {
    String config = projectCacheInstance.getAllProjects().getConfig(this.configName)
        .get().getSubsections(sectionName).toString();
    System.out.println(config);
  }

  /**
   * The sitePath is the root of the gerrit application.
   * This path can be taken from application.properties.
   * @return
   */
  private String extractSitePath() throws IOException {
      return confProps.getGerritRoot();
  }

  /**
   * Returns the name of the config within the All-Projects repo to parse
   * @return
   */
  public String getConfigName() {
    return configName;
  }
}
