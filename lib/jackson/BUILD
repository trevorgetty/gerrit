load("@rules_java//java:defs.bzl", "java_library")

# TREV TODO MApper needed for gitms interface!! fix this.!!
#
java_library(
    name = "jackson-mapper",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = ["//visibility:public"],
    exports = ["@jackson-mapper//jar"],
    runtime_deps = [":jackson-core"],
)

java_library(
    name = "jackson-core",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = [
        "//java/com/google/gerrit/elasticsearch:__pkg__",
        "//plugins:__pkg__",
    ],
    exports = ["@jackson-core//jar"],
)
