load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "jackson-mapper",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = [
        "//lib/wandisco:",
    ],
    exports = ["@jackson-core//jar"],
    deps = [":jackson-core"],
)

java_library(
    name = "jackson-core",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = [
        "//java/com/google/gerrit/elasticsearch:__pkg__",
        "//lib/jackson:jackson-mapper-asl",
        "//plugins:__pkg__",
    ],
    exports = ["@jackson-core//jar"],
)
