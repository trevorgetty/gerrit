load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "wandisco",
    srcs = glob(["**/*.java"]),
    visibility = [
        "//java/com/google/gerrit/pgm:__pkg__",
        "//javatests/com/google/gerrit/pgm/wandisco:__subpackages__",
    ],
    deps = [
        "//gerrit-console-api/src/main/java/com/google/gerrit/gerritconsoleapi/bindings:gerrit-console-api-min-dependency-overrides",
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/pgm/init/api",
        "//java/com/google/gerrit/pgm/util",
        "//java/com/google/gerrit/reviewdb:server",
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/server/ioutil",
        "//java/com/google/gerrit/server/schema",
        "//java/com/google/gerrit/server/util/time",
        "//lib:args4j",
        "//lib:guava",
        "//lib/commons:validator",
        "//lib/flogger:api",
        "//lib/guice",
        "//lib/guice:guice-assistedinject",
        "//lib/httpcomponents:httpclient",
        "//lib/httpcomponents:httpcore",
        "//lib/jgit/org.eclipse.jgit:jgit",
        "//lib/wandisco:gerrit-gitms-interface",
    ],
)
