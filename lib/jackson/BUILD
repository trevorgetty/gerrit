load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "jackson-databind",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = ["//visibility:public"],
    exports = ["@jackson-databind//jar"],
    runtime_deps = [":jackson-core"],
)

java_library(
    name = "jackson-annotations",
    data = ["//lib:LICENSE-Apache2.0"],
    visibility = ["//visibility:public"],
    exports = ["@jackson-annotations//jar"],
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
