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
load("//tools/bzl:java.bzl", "java_library2")

SRCS = "src/main/java/**"
RSRCS = "src/main/resources/"

BASE_DEPS = [
    "//java/com/google/gerrit/common:server",
    "//java/com/google/gerrit/extensions:api",
    "//java/com/google/gerrit/server",
    "//java/com/google/gerrit/sshd:sshd",
    "//java/com/google/gerrit/metrics",
    "//java/com/google/gerrit/server/schema",
    "//lib:guava",
    "//lib/guice:guice",
    "//lib/guice:guice-assistedinject",
    "//lib/guice:guice-servlet",
    "//lib/jgit/org.eclipse.jgit:jgit",
    "//lib/log:api",
    "//lib/log:log4j"
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

# Create a lib full of the source required to run a command line application which
# obtains information from gerrit state OR configuration.
java_library2(
    name = "gerrit-console-api-module",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob([RSRCS + "*"]),
    deps = DEPS,
    visibility = ["//visibility:public"],
)

java_binary(
    name = "console-api",
    main_class = "com.google.gerrit.gerritconsoleapi.CLI_Launcher",
    visibility = ["//visibility:public"],
    runtime_deps = [
        ":gerrit-console-api-module",
    ],
)
