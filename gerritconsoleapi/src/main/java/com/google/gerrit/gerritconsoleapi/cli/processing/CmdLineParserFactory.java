
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
