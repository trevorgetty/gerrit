// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.lucene;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.ConfigUtil;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.util.InfoStream;
import org.eclipse.jgit.lib.Config;

/** Combination of Lucene {@link IndexWriterConfig} with additional Gerrit-specific options. */
class GerritIndexWriterConfig {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final InfoStream VERBOSE = new VerboseOutput();

  private static final ImmutableMap<String, String> CUSTOM_CHAR_MAPPING =
      ImmutableMap.of("_", " ", ".", " ");

  private final IndexWriterConfig luceneConfig;
  private long commitWithinMs;
  private final CustomMappingAnalyzer analyzer;

  /**
  * Note that GerritIndexWriterConfig takes both a cfg object and a name.
  * Name is the name of the subsection, i.e changes_open / change_closed
  */
  GerritIndexWriterConfig(Config cfg, String name) {
    analyzer =
        new CustomMappingAnalyzer(
            new StandardAnalyzer(CharArraySet.EMPTY_SET), CUSTOM_CHAR_MAPPING);
    luceneConfig =
        new IndexWriterConfig(analyzer)
            .setOpenMode(OpenMode.CREATE_OR_APPEND)
            .setCommitOnClose(true);

    int maxMergeCount = cfg.getInt("index", name, "maxMergeCount", -1);
    int maxThreadCount = cfg.getInt("index", name, "maxThreadCount", -1);
    boolean enableAutoIOThrottle = cfg.getBoolean("index", name, "enableAutoIOThrottle", true);
    if (maxMergeCount != -1 || maxThreadCount != -1 || !enableAutoIOThrottle) {
      ConcurrentMergeScheduler mergeScheduler = new ConcurrentMergeScheduler();
      if (maxMergeCount != -1 || maxThreadCount != -1) {
        mergeScheduler.setMaxMergesAndThreads(maxMergeCount, maxThreadCount);
      }
      if (!enableAutoIOThrottle) {
        mergeScheduler.disableAutoIOThrottle();
      }
      luceneConfig.setMergeScheduler(mergeScheduler);
    }

    double m = 1 << 20;
    luceneConfig.setRAMBufferSizeMB(
        cfg.getLong(
                "index",
                name,
                "ramBufferSize",
                (long) (IndexWriterConfig.DEFAULT_RAM_BUFFER_SIZE_MB * m))
            / m);
    luceneConfig.setMaxBufferedDocs(
        cfg.getInt("index", name, "maxBufferedDocs", IndexWriterConfig.DEFAULT_MAX_BUFFERED_DOCS));
    try {
      commitWithinMs =
          ConfigUtil.getTimeUnit(
              cfg, "index", name, "commitWithin", MILLISECONDS.convert(5, MINUTES), MILLISECONDS);
    } catch (IllegalArgumentException e) {
      commitWithinMs = cfg.getLong("index", name, "commitWithin", 0);
    }

    //Checking that it has been set correctly and if so turn the verbose logging on
    //for Lucene.
    if (cfg.getBoolean("index", name, "verboseLogging", false)) {
      // turn on verbose logging for lucene
      luceneConfig.setInfoStream(VERBOSE);
      // Show the current configuration.
      logger.atInfo().log("Current lucene configuration: {}", luceneConfig.toString());
    }
  }

  private static class VerboseOutput extends InfoStream {
    VerboseOutput() { }

    public void message(String component, String message) {
      logger.atInfo().log(message);
    }

    public boolean isEnabled(String component) {
      return true;
    }

    public void close() {}
  }

  CustomMappingAnalyzer getAnalyzer() {
    return analyzer;
  }

  IndexWriterConfig getLuceneConfig() {
    return luceneConfig;
  }

  long getCommitWithinMs() {
    return commitWithinMs;
  }
}
