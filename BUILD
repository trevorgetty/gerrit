load("//tools/bzl:genrule2.bzl", "genrule2")
load("//tools/bzl:pkg_war.bzl", "pkg_war")

package(default_visibility = ["//visibility:public"])

config_setting(
    name = "java9",
    values = {
        "java_toolchain": "@bazel_tools//tools/jdk:toolchain_java9",
    },
)

config_setting(
    name = "java_next",
    values = {
        "java_toolchain": "@bazel_tools//tools/jdk:toolchain_vanilla",
    },
)

# Note version information is obtained from the last Annotated release tag on this branch, plus X commits and
# last commit sha. e.g  v2.16.11-RP-1.10.0.1-DEV-4-ge78a246aac  When it isn't dirty it will simply show the release tag
# itself.  v2.16.11-RP-1.10.0.1
genrule(
    name = "gen_version",
    outs = ["version.txt"],
    cmd = ("cat bazel-out/volatile-status.txt bazel-out/stable-status.txt | " +
           "grep STABLE_BUILD_GERRIT_LABEL | cut -d ' ' -f 2 > $@"),
    stamp = 1,
)

genrule(
    name = "LICENSES",
    srcs = ["//Documentation:licenses.txt"],
    outs = ["LICENSES.txt"],
    cmd = "cp $< $@",
)

pkg_war(
    name = "gerrit",
    ui = "polygerrit",
)

pkg_war(
    name = "headless",
    ui = None,
)

pkg_war(
    name = "polygerrit",
    ui = "polygerrit",
)

pkg_war(
    name = "release",
    context = ["//plugins:core"],
    doc = True,
    ui = "ui_optdbg_r",
)

pkg_war(
    name = "withdocs",
    doc = True,
)

API_DEPS = [
    "//java/com/google/gerrit/acceptance:framework_deploy.jar",
    "//java/com/google/gerrit/acceptance:libframework-lib-src.jar",
    "//java/com/google/gerrit/acceptance:framework-javadoc",
    "//java/com/google/gerrit/extensions:extension-api_deploy.jar",
    "//java/com/google/gerrit/extensions:libapi-src.jar",
    "//java/com/google/gerrit/extensions:extension-api-javadoc",
    "//plugins:plugin-api_deploy.jar",
    "//plugins:plugin-api-sources_deploy.jar",
    "//plugins:plugin-api-javadoc",
    "//gerrit-plugin-gwtui:gwtui-api_deploy.jar",
    "//gerrit-plugin-gwtui:gwtui-api-source_deploy.jar",
    "//gerrit-plugin-gwtui:gwtui-api-javadoc",
    "//gerrit-console-api:console-api_deploy.jar",
    "//gerrit-console-api:libgerrit-console-api-module-src.jar",
]

genrule2(
    name = "api",
    testonly = True,
    srcs = API_DEPS,
    outs = ["api.zip"],
    cmd = " && ".join([
        "cp $(SRCS) $$TMP",
        "cd $$TMP",
        "zip -qr $$ROOT/$@ .",
    ]),
)
