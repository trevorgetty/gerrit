#/********************************************************************************
# * Copyright (c) 2014-2018 WANdisco
# *
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# * http://www.apache.org/licenses/LICENSE-2.0
# *
# * Apache License, Version 2.0
# *
# ********************************************************************************/
load("@rules_java//java:defs.bzl", "java_library")

SRCS = "src/main/java/com/google/gerrit/gerritconsoleapi/**"

RSRCS = "src/main/resources/"

TEST_SRCS = "src/test/java/"

TEST_RSRCS = "src/test/resources/"

BASE_DEPS = [
    "//java/com/google/gerrit/common:server",
    "//java/com/google/gerrit/extensions:api",
    "//java/com/google/gerrit/server:server",
    "//java/com/google/gerrit/sshd:sshd",
    "//lib:guava",
    "//lib/guice:guice",
    "//lib/guice:guice-assistedinject",
    "//lib/guice:guice-servlet",
    "//lib/jgit/org.eclipse.jgit:jgit",
    "//lib/joda:joda-time",
    "//lib/log:api",
    "//lib/log:log4j",
]

# TREV TODO //gerrit-reviewdb, does this need abstracted to work with NoteDB?
DEPS = BASE_DEPS + [
    "//java/com/google/gerrit/reviewdb:server",
    "//lib/wandisco:gerrit-gitms-interface",
    "//lib:gwtorm",
    "//lib:args4j",
    "//lib/auto:auto-value",
    "//lib/log:impl-log4j",
]

java_library(
    name = "gerritconsoleapi",
    srcs = glob([SRCS]),
    resources = glob([RSRCS + "*"]),
    deps = DEPS,
)

java_binary(
    name = "console-api",
    main_class = "com.google.gerrit.gerritconsoleapi.CLI_Launcher",
    visibility = ["//visibility:public"],
    runtime_deps = [
        ":gerritconsoleapi",
    ],
)
