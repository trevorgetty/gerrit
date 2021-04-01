load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "gerrit-console-api-min-dependency-overrides",
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/reviewdb:server",
        "//java/com/google/gerrit/server",
        "//lib:guava",
        "//lib/guice",
        "//lib/guice:guice-assistedinject",
        "//lib/jgit/org.eclipse.jgit:jgit",
        "//lib/log:log4j",
        "@gwtorm-client//jar",
        "@log-api//jar",
    ],
)
