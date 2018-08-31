package com.google.gerrit.gerritconsoleapi;

import com.google.inject.Injector;
import com.wandisco.gerrit.gitms.shared.properties.GitMsApplicationProperties;
import com.wandisco.gerrit.gitms.shared.util.ReplicationUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import com.google.common.base.Strings;
import org.kohsuke.args4j.OptionHandlerFilter;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.kohsuke.args4j.OptionHandlerFilter.ALL;

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
public class GerritCLIArgParse {

  private static final Logger logger = LoggerFactory.getLogger(GerritCLIArgParse.class);

  private CmdLineParser parser;
  private Injector programGuiceContext;
  private Path sitePath;
  private GitMsApplicationProperties confProps;

  @Option(name="--config-name",aliases="-c",  usage="Name of the config file to return.", metaVar="lfs.config", required=true)
  private String configName;

  @Option(name="--git-config", aliases="-g", usage="The location of the .gitconfig configuration file.", metaVar="/home/gitms/.gitconfig", required=true)
  private String gitConfigArg;

  @Option(name="--get-sections", aliases="-s", usage="Display the config sections in the config file.", required=false)
  private boolean sections;

  @Option(name="--get-sub-sections", aliases="-ss", usage="Give the section name to display subSections for.", metaVar="lfs", required=false)
  private String subSectionArg;

  @Option(name="--help", aliases ={"-h", "-?", "/?"}, usage="Show help information about available commands.", required=false)
  private boolean showHelp;

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
  public void doMain(final String... arguments) throws Exception, IOException {

    logger.trace("About to process args. ");

    parser = new CmdLineParser(this);
    // TODO: maybe allow users to set this, but its only a small standalone app. pity it doesn't
    // auto scale...
    parser.setUsageWidth(160);

    try {
      parser.parseArgument(arguments);
    } catch (CmdLineException ex) {

      // hold on if they have used -h, we can ignore required properties.
      // so only log warning about missing required properties if you dont use help!
      if ( !showHelp )
      {
        System.out.println("Warning: Invalid command-line options: " + ex.getMessage());
      }

      displayHelp();
      return;
    }


    // Show help information about the args.
    if ( showHelp )
    {
      logger.trace("Show help selected. ");

      displayHelp();
      return;
    }


    /*
     The gitConfigArg cannot be empty. If it is throw an exception.
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
    sitePath = Paths.get(extractSitePath());


    GuiceConfigurator configurator = new GuiceConfigurator(sitePath);
    programGuiceContext = configurator.getMainInjector();

    processAllProjectsConfig();

    logger.trace("Exiting application. ");
  }

  /**
   * Display Help for this application, and example use.
   */
  private void displayHelp() {

    // Take a newline, and display the help information, and example use.
    System.err.println("");

    System.err.println("**********************************");
    System.err.println("  Gerrit Commandline Api - Help.  ");
    System.err.println("**********************************");

    // display the arguments list, for help.
    System.err.println("java -jar console-api.jar [options...]");
    // print the list of available options
    parser.printUsage(System.err);
    System.err.println();

    // print example use, of just required props.
    System.err.println("  Example: java -jar console-api.jar " + parser.printExample(OptionHandlerFilter.REQUIRED));
  }

  /**
   * Using Guice to get an instance from the Injector for ProjectCache which
   * allows access to All-Projects configs
   */
  private void processAllProjectsConfig() {

    AllProjectsCommands allProjectsCommands = new AllProjectsCommands(programGuiceContext, configName);
    if (sections) {
      allProjectsCommands.displaySections();
    } else if (!Strings.isNullOrEmpty(subSectionArg)){
      allProjectsCommands.displaySubSectionsForSection(subSectionArg);
    } else{
      allProjectsCommands.displayConfig();
    }
  }

  /**
   * The sitePath is the root of the gerrit application.
   * This path can be taken from application.properties.
   * @return
   */
  private String extractSitePath() throws IOException {
      return confProps.getGerritRoot();
  }


}
