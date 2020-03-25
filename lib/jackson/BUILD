load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "jackson-core",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = [
        "//java/com/google/gerrit/elasticsearch:__pkg__",
        "//plugins:__pkg__",
    ],
    exports = ["@jackson-core//jar"],
)


# TREV TODO MApper needed for gitms interface NOT the com.fasterxml version!!
#
java_library(
    name = "codehaus-jackson-mapper-asl",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = ["//lib/wandisco:__pkg__"],
    exports = ["@codehaus-jackson-mapper-asl//jar"],
    runtime_deps = [":codehaus-jackson-core-asl"],
)

java_library(
    name = "codehaus-jackson-core-asl",
    data = ["//lib:LICENSE-Apache2.0"],
    exports = ["@codehaus-jackson-core-asl//jar"],
)
