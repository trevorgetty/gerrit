package com.google.gerrit.gerritconsoleapi.cli.commands;


import com.google.common.base.Strings;
import com.google.gerrit.gerritconsoleapi.AllProjectsCommands;
import com.google.gerrit.gerritconsoleapi.bindings.GuiceConfigurator;
import com.google.gerrit.gerritconsoleapi.cli.processing.CliCommandItemBase;
import com.google.gerrit.gerritconsoleapi.cli.processing.CmdLineParserFactory;
import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ReplicationUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.wandisco.gerrit.gitms.shared.util.ReplicationUtils.getGitConfigLocationProperty;

@CommandMetaData(name = "config", description = "Project configuration information")
public class ConfigurationCommand extends CliCommandItemBase {


  private static final Logger logger = LoggerFactory.getLogger(ConfigurationCommand.class);

  private Injector programGuiceContext;
  private Path sitePath;
  private GitMsApplicationProperties confProps;

  public ConfigurationCommand(){
    super( "config");
  }

  @Option(name = "--config-name", aliases = "-c", usage = "Name of the config file to return.", metaVar = "lfs.config", required = true)
  private String configName;

  // No longer make this required, I now default this using the standard rules employed by the scripts which is environment $GIT_CONFIG, or user.home/.gitconfig
  // if you specify this arg it will overrule these.
  @Option(name = "--git-config", aliases = "-g", usage = "The location of the .gitconfig configuration file.", metaVar = "~/.gitconfig or /opt/wandisco/gitms/.gitconfig", required = false)
  private String gitConfigArg;

  @Option(name = "--get-sections", aliases = "-s", usage = "Display the config sections in the config file.", required = false)
  private boolean sections;

  @Option(name = "--get-sub-sections", aliases = "-ss", usage = "Give the section name to display subSections for.", metaVar = "lfs", required = false)
  private String subSectionArg;


  @Override
  public void execute() throws LogAndExitException {

    /*
     * If the gitconfig arg is empty, try using our standard args to get it from environment.
     */
    if ( Strings.isNullOrEmpty(gitConfigArg) ){
      gitConfigArg = getGitConfigLocationProperty();
    }

    /*
     The gitConfigArg cannot be empty by this stage. If it is throw an exception.
     */
    if (gitConfigArg.isEmpty()) {
      logger.trace("Invalid git configuration args. ");
      throw new LogAndExitException("The \"--git-config\" argument must specify the full path to the \".gitconfig\" file ");
    }

  /*
   The full path to the .gitconfig file must be specified
   */
    File gitconfigFile = new File(gitConfigArg);
    if (!gitconfigFile.exists() || gitconfigFile.isDirectory()) {
      logger.trace("Invalid git configuration it wasn't a valid file on disk. ");

      throw new LogAndExitException("The \".gitconfig\" file provided is invalid or a directory. " +
          "Please supply the full path to this file.");
    }

    /*
     * To enforce that the rest of the application now uses this gitconfig environment, regardless of where it came from.
     * Set the full path to the .gitconfig file as a system property
     */
    System.setProperty("GIT_CONFIG", gitConfigArg);

    /*
     Calling into gerrit.gitms.shared library to parse the GitMS application.properties
     */
    try {
      confProps = ReplicationUtils.parseGitMSConfig();
    } catch (IOException e) {
      throw new LogAndExitException("Problem occurred when retrieving GitMS configuration. Error Details: ", e);
    }

    /*
     The sitePath will be the gerrit.root declared within the application.properties.
     With the sitePath declared, the Guice bindings to the application classes can be performed.
     */
    try {
      sitePath = Paths.get(extractSitePath());
    } catch (IOException e) {
      throw new LogAndExitException("Problem occurred when retrieving GitMS configuration. Error Details: ", e);
    }


    GuiceConfigurator configurator = new GuiceConfigurator(sitePath);
    programGuiceContext = configurator.getMainInjector();

    processAllProjectsConfig();

    logger.trace("Exiting application. ");
  }


  /**
   * Using Guice to get an instance from the Injector for ProjectCache which
   * allows access to All-Projects configs
   */
  private void processAllProjectsConfig() {

    AllProjectsCommands allProjectsCommands = new AllProjectsCommands(programGuiceContext, configName);
    if (sections) {
      allProjectsCommands.displaySections();
    } else if (!Strings.isNullOrEmpty(subSectionArg)) {
      allProjectsCommands.displaySubSectionsForSection(subSectionArg);
    } else {
      allProjectsCommands.displayConfig();
    }
  }

  /**
   * The sitePath is the root of the gerrit application.
   * This path can be taken from application.properties.
   *
   * @return
   */
  private String extractSitePath() throws IOException {
    return confProps.getGerritRoot();
  }

}
