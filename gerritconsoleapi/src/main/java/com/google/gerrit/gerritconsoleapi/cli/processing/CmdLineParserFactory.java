package com.google.gerrit.gerritconsoleapi.cli.processing;

import org.kohsuke.args4j.CmdLineParser;

/**
 * Factory class, to create the command line parser with a default set of options, to make it consistent across commands.
 */
public final class CmdLineParserFactory
{

  private static final int CLI_OUTPUT_WIDTH_OF_USAGE = 140;

  /**
   * Static factory to create our command line parser in a consistent way.
   *
   * @param beanToParse
   * @return
   */
  public static CmdLineParser createCmdLineParser(Object beanToParse)
  {
    // when we move versions over 2.10, then start to use ParserProperties here, and get autoscaling of text.
    CmdLineParser parser = new CmdLineParser(beanToParse);
    parser.setUsageWidth(CLI_OUTPUT_WIDTH_OF_USAGE);
    return parser;
  }
}
