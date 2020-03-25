load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "gerrit-gitms-interface",
    data = ["//lib:LICENSE-WANdisco"],
    visibility = ["//visibility:public"],
    exports = ["@gerrit-gitms-interface//jar"],
    runtime_deps = [
        "//lib/jackson:codehaus-jackson-mapper-asl",
        "//lib/jgit/org.eclipse.jgit:jgit",
    ],
)
