load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "wandisco_pgm_tests",
    srcs = glob(["**/*.java"]),
    deps = [
        "//gerrit-console-api/src/main/java/com/google/gerrit/gerritconsoleapi/bindings:gerrit-console-api-min-dependency-overrides",
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/pgm/wandisco",
        "//java/com/google/gerrit/reviewdb:server",
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//lib/powermock:powermock-core",
        "@guava-retrying//jar",
        "@gwtorm-client//jar",
        "@jgit-lib//jar",
        "@mockito//jar",
        "@truth//jar",
    ],
)
