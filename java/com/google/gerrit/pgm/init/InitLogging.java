// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.pgm.init;

import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.pgm.init.api.Section;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class InitLogging implements InitStep {
  private static final String CONTAINER = "container";
  private static final String JAVA_OPTIONS = "javaOptions";
  private static final String FLOGGER_BACKEND_PROPERTY = "flogger.backend_factory";
  private static final String FLOGGER_LOGGING_CONTEXT = "flogger.logging_context";
  private static final String FLOGGER_BACKEND_VALUE = "com.google.common.flogger.backend.slf4j.Slf4jBackendFactory#getInstance";

  private final Section container;

  @Inject
  public InitLogging(Section.Factory sections) {
    this.container = sections.get(CONTAINER, null);
  }

  @Override
  public void run() throws Exception {
    List<String> javaOptions = new ArrayList<>(Arrays.asList(container.getList(JAVA_OPTIONS)));
    if (!isSet(javaOptions, FLOGGER_BACKEND_PROPERTY) || !isSLF4JLogging(javaOptions)) {
      final List<String> duplicateLoggingConfig = javaOptions.stream()
          .filter(op -> op.contains("-D" + FLOGGER_BACKEND_PROPERTY))
          .collect(Collectors.toList());
      javaOptions.removeAll(duplicateLoggingConfig);
      javaOptions.add(getJavaOption(FLOGGER_BACKEND_PROPERTY, FLOGGER_BACKEND_VALUE));
    }
    if (!isSet(javaOptions, FLOGGER_LOGGING_CONTEXT)) {
      javaOptions.add(
          getJavaOption(
              FLOGGER_LOGGING_CONTEXT,
              "com.google.gerrit.server.logging.LoggingContext#getInstance"));
    }
    container.setList(JAVA_OPTIONS, javaOptions);
  }

  private boolean isSLF4JLogging(final List<String> javaOptions) {
    return javaOptions.stream()
        .anyMatch(
            option -> {
              return option.contains("-D" + FLOGGER_BACKEND_PROPERTY) && option.contains(FLOGGER_BACKEND_VALUE);
            }
        );
  }

  private static boolean isSet(List<String> javaOptions, String javaOptionName) {
    return javaOptions.stream()
        .anyMatch(
            o ->
                o.startsWith("-D" + javaOptionName + "=")
                    || o.startsWith("\"-D" + javaOptionName + "="));
  }

  private static String getJavaOption(String javaOptionName, String value) {
    return String.format("-D%s=%s", javaOptionName, value);
  }
}
