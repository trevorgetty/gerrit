
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
 
// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.pgm.util;

import com.google.gerrit.common.FileUtil;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.util.SystemLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import net.logstash.log4j.JSONEventLayoutV1;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.jgit.lib.Config;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class ErrorLogFile {
  static final String LOG_NAME = "error_log";
  static final String JSON_SUFFIX = ".json";
  static final Logger logger = Logger.getLogger(ErrorLogFile.class);

  public static void errorOnlyConsole() {
    LogManager.resetConfiguration();

    PatternLayout layout = new PatternLayout();
    layout.setConversionPattern("%-5p %c %x: %m%n");

    ConsoleAppender dst = new ConsoleAppender();
    dst.setLayout(layout);
    dst.setTarget("System.err");
    dst.setThreshold(Level.ERROR);
    dst.activateOptions();

    Logger root = LogManager.getRootLogger();
    root.removeAllAppenders();
    root.addAppender(dst);
    root.setLevel(Level.INFO);
  }

  public static void initLoggingBridge() {
    java.util.logging.LogManager.getLogManager().reset();
    SLF4JBridgeHandler.install();
  }

  public static LifecycleListener start(Path sitePath, Config config) throws IOException {
    Path logdir =
        FileUtil.mkdirsOrDie(new SitePaths(sitePath).logs_dir, "Cannot create log directory");
    if (SystemLog.shouldConfigure()) {
      initLogSystem(logdir, config);
      initWandiscoLogging(sitePath);
    }

    return new LifecycleListener() {
      @Override
      public void start() {}

      @Override
      public void stop() {
        LogManager.shutdown();
      }
    };
  }

  private static void initLogSystem(Path logdir, Config config) {
    Logger root = LogManager.getRootLogger();
    root.removeAllAppenders();

    boolean json = config.getBoolean("log", "jsonLogging", false);
    boolean text = config.getBoolean("log", "textLogging", true) || !json;
    boolean rotate = config.getBoolean("log", "rotate", true);
    final PatternLayout pattern = new PatternLayout("[%d] [%t] %-5p %c %x: %m%n");

    if (text) {
      root.addAppender(
          SystemLog.createAppender(
              logdir, LOG_NAME, pattern, rotate));
      final ConsoleAppender stdout = new ConsoleAppender();
      stdout.setLayout(pattern);
      stdout.setTarget("System.out");
      stdout.setThreshold(Level.INFO);
      stdout.activateOptions();
      LogManager.getRootLogger().addAppender(stdout);
    }

    if (json) {
      root.addAppender(
          SystemLog.createAppender(
              logdir, LOG_NAME + JSON_SUFFIX, new JSONEventLayoutV1(), rotate));
    }
  }

  private static void initWandiscoLogging(final Path sitePath) {
    // build up the list of all the values in the file
    Properties prop = new Properties();
    try{
      File wdLogging = Paths.get(sitePath.toFile().getAbsolutePath(),"/etc/wd_logging.properties").toFile();
      if(!wdLogging.exists()){
        logger.info("Cannot find wd_logging.properties skipping");
        return;
      }
      try ( FileInputStream stream = new FileInputStream(wdLogging)){
        prop.load(stream);

        Iterator<Map.Entry<Object, Object>> iter = prop.entrySet().iterator();
        Map.Entry entry;

        while(iter.hasNext()){
          entry = iter.next();
          String className = entry.getKey().toString();
          String logLevelVal = entry.getValue().toString();
          Level logLevel = Level.toLevel(logLevelVal, Level.INFO);

          LogManager.getLogger(className).setLevel(logLevel);
        }
        logger.info("Started WANdisco Logging");
      }catch (IOException e){
        logger.warn("Something went wrong \n"+e.getMessage());
      }
    }
    catch (NullPointerException e){
      logger.error("Something went wrong  \n"+e.getMessage());
    }
  }
}
