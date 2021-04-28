load("//tools/bzl:maven_jar.bzl", "MAVEN_CENTRAL", "MAVEN_LOCAL", "WANDISCO_ASSETS", "maven_jar")

_JGIT_VANILLA_VERS = "5.1.15"
_DOC_VERS = "5.1.15.202012011955-r"  # Set to _JGIT_VANILA_VERS unless using a snapshot

# Defines the WD postfix
_POSTFIX_WD = "-WDv1"

# Defines the version of jgit, even the replicated version of jgit, should be no external use of the vanilla version.
_JGIT_VERS = _JGIT_VANILLA_VERS + _POSTFIX_WD

JGIT_DOC_URL = "https://archive.eclipse.org/jgit/site/" + _DOC_VERS + "/apidocs"

_JGIT_REPO = WANDISCO_ASSETS  # Leave here even so can be set to different maven repos easily.

# set this to use a local version.
# "/home/<user>/projects/jgit"
LOCAL_JGIT_REPO = ""

def jgit_repos():
    if LOCAL_JGIT_REPO:
        native.local_repository(
            name = "jgit",
            path = LOCAL_JGIT_REPO,
        )
        jgit_maven_repos_dev()
    else:
        jgit_maven_repos()

def jgit_maven_repos_dev():
    # Transitive dependencies from JGit's WORKSPACE.
    maven_jar(
        name = "hamcrest-library",
        artifact = "org.hamcrest:hamcrest-library:1.3",
        sha1 = "4785a3c21320980282f9f33d0d1264a69040538f",
    )

    maven_jar(
        name = "jzlib",
        artifact = "com.jcraft:jzlib:1.1.1",
        sha1 = "a1551373315ffc2f96130a0e5704f74e151777ba",
    )

def jgit_maven_repos():
    maven_jar(
        name = "jgit-lib",
        artifact = "org.eclipse.jgit:org.eclipse.jgit:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "82a3698ffb55828bb27f5b1d3df95e055643aa5d",
        src_sha1 = "328c11caf24c027ae8125bbcc667fb540ab9ccf5",
        unsign = True,
    )
    maven_jar(
        name = "jgit-servlet",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.server:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "00fc654148029bfba197616cb009caa73d0e5d17",
        unsign = True,
    )
    maven_jar(
        name = "jgit-archive",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.archive:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "6c289dec2d1ba51c0028518d652d1909c2bcf5a8",
    )
    maven_jar(
        name = "jgit-junit",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.junit:" + _JGIT_VERS,
        repository = _JGIT_REPO,
        sha1 = "32eab17b25ff04946771aadc572a446407116d23",
        unsign = True,
    )

    # Added to support lfs as core plugin from gerrit workspace
    maven_jar(
        name = "jgit-http-apache",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.http.apache:" + _JGIT_VERS,
        sha1 = "4f4608f469daf078b7fe1851b97849fb0d2be845",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

    maven_jar(
        name = "jgit-lfs",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.lfs:" + _JGIT_VERS,
        sha1 = "731f318bf1fa43b2968cccf21ecd545f21e508d6",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

    maven_jar(
        name = "jgit-lfs-server",
        artifact = "org.eclipse.jgit:org.eclipse.jgit.lfs.server:" + _JGIT_VERS,
        sha1 = "3896fa684ae4bafda596ca2291b3089566ed452a",
        repository = _JGIT_REPO,
        unsign = True,
        exclude = [
            "about.html",
            "plugin.properties",
        ],
    )

def jgit_dep(name):
    mapping = {
        "@jgit-archive//jar": "@jgit//org.eclipse.jgit.archive:jgit-archive",
        "@jgit-junit//jar": "@jgit//org.eclipse.jgit.junit:junit",
        "@jgit-lib//jar": "@jgit//org.eclipse.jgit:jgit",
        "@jgit-lib//jar:src": "@jgit//org.eclipse.jgit:libjgit-src.jar",
        "@jgit-servlet//jar": "@jgit//org.eclipse.jgit.http.server:jgit-servlet",
        "@jgit-http-apache//jar": "@jgit//org.eclipse.jgit.http.apache:jgit-http-apache",
        "@jgit-lfs//jar": "@jgit//org.eclipse.jgit.lfs:jgit-lfs",
        "@jgit-lfs-server//jar": "@jgit//org.eclipse.jgit.lfs.server:jgit-lfs-server",
    }

    if LOCAL_JGIT_REPO:
        return mapping[name]
    else:
        return name
