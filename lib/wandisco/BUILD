load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "gerrit-gitms-interface",
    data = ["//lib:LICENSE-WANdisco"],
    visibility = ["//visibility:public"],
    exports = ["@gerrit-gitms-interface//jar"],
    runtime_deps = [
        "//lib/jackson:jackson-databind",
        "//lib/jackson:jackson-annotations",
#        Can't cyclic link, really we rely on a vanilla version but as long
#         as you check the users of this also have jgit-RP version we are fine...
#        "//lib/jgit/org.eclipse.jgit:jgit",
    ],
)
