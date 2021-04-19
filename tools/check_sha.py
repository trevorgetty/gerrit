#!/usr/bin/env python
# Copyright (c) 2021 WANdisco, Inc.  All rights reserved.
#
# This script automates the process of updating asset SHAs in bazel
# files (e.g. jgit.bzl and WORKSPACE) by checking for physical assets in
# a user provided location and calculating the new SHA value. You can
# tell it where you want to look for reference assets (A maven
# repository, or directory on disk for local builds), by default it will
# use whichever repository is specified on an asset-by-asset basis from
# the build files.
#
# The following options are supported:
#  -c/--check      Will just validate the contents of jgit.bzl and WORKSPACE
#                  and report on missing or duplicated SHAs.
#  -e/--enable     In combination with '-p' if any SHAs being updated are
#                  commented out, this flag will uncomment them.
#  -f/--filter     Allows a custom asset filter to be specified as a regular
#                  expression. Default filter will match com.wandisco and
#                  org.eclipse.jgit.
#  -j/--jar_store  This value will override the default location for reference
#                  assets and can be a repository name, directory, or bazel
#                  file.
#  -p/--patch      This flag will update assets in jgit.bzl and WORKSPACE
#                  with new SHAs calculated from corresponding reference
#                  assets.
#  -v/--verbose    Will provide more verbose logging during execution.
#  -x/--debug      Will provide a lot of debug information during execution.
#
# Typical usage scenarios might be:
#
#   1) To simply grab the latest SHAs for each asset from the repository
#   they are stored and update. Essentially a default 'update-assets'
#   operation:
#
#       $ tools/check_sha.py -vpe
#
#   2) If you have locally built a library, e.g. jgit, and want to
#   switch your build to use maven_local as the source and just grab the
#   SHAs for jgit from the project directory directly.
#
#       $ tools/check_sha.py -vpe -j ~/dev/projects/jgit/
#
# TODO: Can we also handle updating src_sha fields... We'd need to look
#       for a '-source' jar in the repo such that the remainder of the asset
#       name is a match to the current asset.

from __future__ import print_function

import argparse
import re
import subprocess
from copy import copy
from hashlib import sha1
from os import getcwd, getpid, path, remove, walk
from subprocess import CalledProcessError
from sys import stderr
from tempfile import gettempdir

## Globals
debug = False
verbose = False

# Asset filtering options: We allow a filter parameter to be passed so
# that assets being operated on are restricted. The default filter is
# assets containing 'com.wandisco' or 'org.eclipse.jgit' in the
# artifact.
default_filter = '.*(com\.wandisco|org\.eclipse\.jgit).*'

# This is our map of repository locations that we know about. These may
# appear in bazel files as variables so we can subsitute the known
# values, but a user may also supply one of these as a jar location so
# we can check and update SHAs against published repository assets.
known_repos = {'GERRIT':'GERRIT:',
               'GERRIT_API':'GERRIT_API:',
               'MAVEN_CENTRAL':'MAVEN_CENTRAL:',
               'MAVEN_LOCAL':'MAVEN_LOCAL:',
               'ECLIPSE':'ECLIPSE:',
               'WANDISCO_ASSETS':'WANDISCO:'}


##-------------------------------------------------------------------------
## Data Structures
##-------------------------------------------------------------------------

class Asset:
    def __init__(self):
        self.name = None
        self.artifact = None
        self.repository = None
        self.sha1 = None
        self.src_sha1 = None

    def __copy__(self):
        new = Asset()
        for key in self.__dict__:
            new[key] = self.__dict__[key]
        return new

    def __setitem__(self, key, val):
        """Provide a map style setter for Assets but reject keys that are not
        defined as members by the initializer.
        """
        if key in self.__dict__:
            setattr(self, key, val)

    def __str__(self):
        """Generic Asset to string as __ns__.Asset<key0=val0, ...>."""
        return  str(self.__class__) + '<' + \
            ', '.join((str(item) + '=' + str(self.__dict__[item]) \
                       for item in sorted(self.__dict__))) + '>'


class AssetGroup:
    def __init__(self, source_type, source):
        """Initialize an AssetGroup storing the source and source_type of where
        the assets come from. Source type should be one of 'FILE',
        'DIR', or 'REPO' (though this is really just for future
        reference.) Assets will be initialized to an empty list. env is
        only relevant to file sourced groups so it will be None by
        default.
        """
        self.source_type = source_type
        self.source = source
        self.assets = []
        self.env = None

    def make_replacement_set(self, other):
        """Compare the assets from this group with another group and build a table
        of shas to replace. Only shas in a file should be out of date so
        raise an error if self is not sourced from a file.
        The structure returned is a list of:
        [(artifact, old-sha, new-sha), ...]
        """
        if self.source_type is not 'FILE':
            raise TypeError, "Not supported when self is not sourced from a file."

        return [(a.artifact, a.sha1, b.sha1) for a in self.assets for b in other.assets
                if a.artifact == b.artifact and a.sha1 != b.sha1 and None not in [a.sha1, b.sha1]]

    def filter_assets(self, matcher):
        """Restrict assets in this group to only those whose artifact matches
        the given regular expression `matcher'.
        This operation will modify self destructively!
        """
        self.assets = [asset for asset in self.assets
                       if (asset.artifact or asset.name)
                       and re.match(matcher, asset.artifact or asset.name)]

    def assets_missing_shas(self):
        """Return a list of assets in this group with no SHA1."""
        return [asset.name for asset in self.assets if asset.sha1 is None]

    def assets_with_duplicate_shas(self):
        """Return a list of assets in this group whose SHA1 values match."""
        return [(asset.name, other.name, asset.sha1)
                for asset in self.assets for other in self.assets
                if asset.artifact != other.artifact and asset.sha1 == other.sha1]

    def print_validation_summary(self):
        """Print a list of assets in this group with no SHA1."""
        no_shas = self.assets_missing_shas()
        duplicate_shas = self.assets_with_duplicate_shas()

        no_sha_count = len(no_shas)
        if no_sha_count > 0:
            print("The following assets in {0} have no SHA1:".format(self.source))
            for asset in no_shas:
                print(asset)
        else:
            print("All assets in in {0} have SHA1 values.".format(self.source))

        duplicate_sha_count = len(duplicate_shas)
        if duplicate_sha_count > 0:
            print("The following assets in {0} have duplicate SHA1 values:".format(self.source))
            for pair in duplicate_shas:
                print("{0},{1}: {2}".format(pair[0], pair[1], pair[2]))
        else:
            print("No duplicate SHA1 values in {0}".format(self.source))

        return no_sha_count + duplicate_sha_count

    def print_contents_summary(self):
        """For debug purposes: Dump out a summary of this asset group including
        source and type, assets added to this asset group and also the
        resolved environment variables (if present).
        """
        hr = '-' * 80
        print(hr)
        print("Assets under: {0} ({1})".format(self.source, self.source_type))
        if self.env:
            print(hr)
            print("Environment:")
            for k,v in self.env.items():
                print("{0}={1}".format(k,v))
        print(hr)
        for asset in self.assets:
            print("Found: {0} {1} ({2})".format(asset.name, asset.artifact, asset.sha1))
        print("End: {0}".format(self.source))
        print(hr + '\n')

    def __str__(self):
        """Return a string representation of the contents of this asset group.
        i.e.
        Source {type}: {source}
        <Asset0>
        ...
        <AssetN>
        """
        title = "Source {0}: {1}".format(self.source_type, self.source)
        return title + '\n' + '\n'.join(str(asset) for asset in self.assets)


##-------------------------------------------------------------------------
## Procedures
##-------------------------------------------------------------------------

## The following four procedures do the bulk of loading assets depending
## on the source type: bazel_file, directory_walk, repository and
## default_repository.

def assets_from_bazel_file(filename):
    """Create a list of asset data by reading lines in a .bzl or workspace
    file.  The file should be formatted such that assets are defined by
    maven_jar blocks e.g.:

        maven_jar(
          name = "gerrit-gitms-interface",
          artifact = "com.wandisco:gerrit-gitms-interface:" + _GERRIT_GITMS_VERSION,
          repository = WANDISCO_ASSETS,
          sha = "b8f705851bf77393a403466ada224e9a53c13b95",
          #src_sha = "68f0ece9b1e56ac26f8ce31d9938c504f6951bca"
        )

    Some keys are optional but we'll read them if they exist
    (e.g. src_sha). If a key has been commented out we'll still read the
    current value, as an option exists to uncomment the key on
    patching. Other keys are not interesting to us.

    We also need to keep track of variables while scanning the file so
    that we can resolve these and substitute them into the asset,
    e.g. artifact and repository in the example above need to have
    _GERRIT_GITMS_VERSION and WANDISCO_ASSETS resolved to values.
    """
    asset_group = AssetGroup('FILE', filename)
    assets = []
    env = {}

    with open(filename) as file_in:
        current_asset = None
        for line in file_in:
            line = line.strip()
            # Match lines which begin or end a maven_jar section:
            if re.match("^maven_jar\(.*$", line):
                current_asset = Asset()
            if current_asset and re.match("^\)$", line):
                assets.append(current_asset)
                current_asset = None

            # We want to match lines that look like variable
            # declarations in bazel, (i.e. lines containing an '='
            # character with at least 1 non-whitespace character before
            # and after.
            if re.match("[^=]+=[^=]+", line):
                k,v = parse_property(line)

                # If we're outside of a maven_jar section (no current
                # asset) then we can interpret the line as a variable
                # declaration and save it up for later. (There might be
                # a few false positives here but it shouldn't matter for
                # the variables we're interested in.)
                if not current_asset:
                    env[k] = v
                # Otherwise, interpret the line as an asset field:
                else:
                    current_asset[k] = v

    # After scanning the file we have all the data we need and can do a
    # bit of cleanup: We want to resolve variable references in our
    # environment (i.e. variables that refer to other variables), and
    # then resolve the variables that occur in each asset,
    # e.g. repository and version tags.

    # Resolve Variables in environment:
    updates = {}
    for k,v in env.items():
        # Sub in other referenced variables.
        updated_val = v.replace(' + ', '')
        for l,m in env.items():
            updated_val = updated_val.replace(l,m)
        updates[k] = updated_val
        # Sub in known repository prefices if encountered.
        if v in known_repos:
            updates[k] = known_repos[v]
    env.update(updates)

    # Now we can substitute in any variables that occur within
    # assets. This will only affect the artifact and repository fields.
    for asset in assets:
        # Substitute env variables into assets.
        for k,v in env.items():
            if asset.artifact:
                asset.artifact = asset.artifact.replace(' + ','')
                asset.artifact = asset.artifact.replace(k,v)
            if asset.repository:
                asset.repository = asset.repository.replace(k,v)
        # Repository might not have been defined as a variable but if the
        # asset still refers to a known repo we can sub in the value.
        if asset.repository in known_repos:
            asset.repository = known_repos[asset.repository]

    asset_group.assets = assets
    asset_group.env = env
    return asset_group


def assets_from_directory_walk(pathname, matcher=".+.jar$"):
    """Create a list of assets by walking the given directory tree looking
    for .jar file and build the assets by inspecting the manifest, filename
    and hashing the contents with sha1.

    Optional parameter `matcher' can be used to change the strategy for
    filtering wanted filenames. The default matches all jars, but to
    just pick up local builds you may want to specify "*-SNAPSHOT.jar".
    """
    asset_group = AssetGroup('DIR', pathname)

    filenames = file_walk(pathname, matcher)
    assets = [asset_from_jar(f)  for f in filenames]

    asset_group.assets = assets
    return asset_group


def assets_from_repository(repo_name, reference_assets):
    """Create a list of assets by iterating `reference_assets' and
    attempting to locate each asset repository denoted by
    `repo_name'. Each reference asset will be download by delegating to
    tools/download_file.py then the asset created from the .jar.

    repo_name can be a prefix resolvable by download_file.py if it ends
    in a ':' character, e.g. MAVEN_CENTRAL:, or it can be an entry in
    the known_repos table, e.g. WANDISCO_ASSETS.
    """
    asset_group = AssetGroup('REPO', repo_name)
    assets = []

    # Resolve repository name, if we can't we need to raise an error:
    if repo_name.endswith(':'):
        pass
    elif repo_name in known_repos:
        repo_name = known_repos[repo_name]
    else:
        raise ValueError, "Unknown repository: {0}".format(repo_name)

    for asset in reference_assets:
        assets.append(asset_from_repo(asset, repo_name))

    asset_group.assets = assets
    return asset_group


def assets_from_default_repository(reference_assets):
    """Create a list of assets by iterating reference_assets and attempting
    to locate each asset in the repository defined by the asset. If the
    asset does not name a repository it will be skipped. Each reference
    asset will be downloaded by delegating to tools/download_file.py and
    the new asset created from the .jar file.
    """
    asset_group = AssetGroup('REPO', "(Repository from Asset)")
    assets = []

    for asset in reference_assets:
        if not asset.repository:
            if verbose:
                print("Asset {0} does not name a repository, skipping...".format(asset.name), file=stderr)
            continue

        assets.append(asset_from_repo(asset, asset.repository))

    asset_group.assets = assets
    return asset_group


def asset_from_repo(asset, repo_name):
    """Given an input `asset', will look on the repository given in
    `repo_name' for the latest corresponding asset and return a copy with
    the new SHA1 digest.
    """
    vendor,name,version = asset.artifact.split(':')

    jar_name = "{0}-{1}.jar".format(name, version)
    tmp_file = path.join(gettempdir(), "{0}.{1}".format(jar_name, getpid()))
    url = '/'.join([repo_name,
                    vendor.replace('.','/'),
                    name,
                    version,
                    jar_name])

    new_asset = copy(asset)

    if (verbose):
        print("Checking {0} for {1} ({2})...".format(repo_name, jar_name, asset.sha1))

    # We always pass a fake SHA1 to this command as we want it to fail
    # the cache lookup and tell us the actual SHA1 in the repository.
    cmd = ["tools/download_file.py", "-o", tmp_file, "-u", url, "-v", 'xxxxxxxxxxx']

    try:
        subprocess.check_output(cmd, stderr=subprocess.STDOUT)
    except CalledProcessError as e:
        new_sha = None
        for line in e.output.split('\n'):
            # Failed command output will contain:
            #   expected xxxxxxxxxxxx
            #   received abcdef...123456
            # We want to keep the received line which is the repo SHA1.
            if line.startswith('received'):
                new_sha = line.split(' ')[1]

        # If new_asset.sha1 is not updated by a found asset the
        # subsequent replace operation will be a no-op which is
        # safe. The verbose log will show found/not found assets.
        if new_sha:
            if (verbose):
                print("\tFound: {0}".format(new_sha))
            new_asset.sha1 = new_sha
        elif verbose:
            print("\tNot found")

    if path.isfile(tmp_file):
        remove(tmp_file)

    return new_asset


def asset_from_jar(filename):
    """Create an Asset by analyzing a .jar file on disk.

    This procedure will try to unzip and read the jar manifest to
    populate the vendor and version information, parse the filename to
    create the asset name.

    asset.artifact will be set to a value of 'vendor:name:version' if
    all three values were parsed from the filename and
    manifest. otherwise this field will be left blank (None).

    asset.name will be the name parsed from the filename if
    vendor:name:version were successfully parsed. Otherwise it will be
    set to the full filename.

    asset.sha1 will be set to the value of running sha1sum on the file
    contents.

    Note: There is a python package called java-manifest which might do
    this better but we're not doing anything very sophisticated.
    """
    asset = Asset()

    # We can always sha the jar file, so at the very least we should
    # have a filename and a sha1 digest in the asset.
    asset.name = filename.split('/')[-1]
    asset.sha1 = sha1sum(filename)

    cmd = ["unzip", "-p", filename, "META-INF/MANIFEST.MF"]
    try:
        manifest = subprocess.check_output(cmd)

        # Temp asset vars.
        vendor = None
        version = None
        name = None

        # Manifest is going to have some of the data we need to build
        # the artifact: What we need from the manifest is the vendor
        # prefix (to form groupId), and version. Look for
        # Implementation-Version and Implementation-Vendor-Id.  Fall
        # back to Specification-Version. The important thing is that the
        # constructed artifact matches the equivalent field we read from
        # a bazel file so we can match up the SHAs to replace.
        for line in manifest.split('\n'):
            if not re.match("[^:]+:[^:]+", line):
                continue

            k,v = parse_property(line, delim=':')

            if k == "Implementation-Vendor-Id":
                vendor = v
            if k == "Implementation-Version":
                version = v
            if k == "Specification-Version" and not version:
                version = v

        # The asset name is just the part of the filename before the
        # version info. We can't just rely on the title from the
        # manifest as it might not exist, or have an unexpected
        # format. If we have a version at this point we can build the
        # name and artifact. If not we can leave the name defaulted to
        # the filename.
        if version:
            name = filename.split("-" + version)[0]
            name = name.split('/')[-1]

        # We need all three manifest entries to build an artifact coordinate.
        if name and vendor and version:
            asset.artifact = "{0}:{1}:{2}".format(vendor, name, version)
            asset.name = name

    except Exception as e:
        print("Command failed: {0}\n{1}".format(' '.join(cmd), e), file=stderr)

    return asset


def replace_shas_in_file(groupA, groupB, enable_shas = False):
    """Replace the SHAs for assets in `groupA' with the SHAs for
    corresponding assets in `groupB'.

    groupA must be an AssetGroup whose source_type is 'FILE' and this
    procedure will delegate to 'sed' to perform a file modification.

    If optional argument `enable_shas' is true then any SHA values being
    replaced will also be uncommented.
    """
    replacements = groupA.make_replacement_set(groupB)
    for artifact,old,new in replacements:
        if verbose:
            print("Replacing {0}: {1} => {2}".format(artifact, old, new))

        cmd = ["sed", "-E", "-i", groupA.source, "-e", "s/{0}/{1}/".format(old, new)]

        if enable_shas:
            cmd += ["-e", "s/^([^#]*)#(.+{0}.+)$/\\1\\2/".format(new)]

        try:
            subprocess.check_output(cmd)
        except Exception as e:
            print("Command Failed: {0}\n{1}".format(' '.join(cmd), e), file=stderr)


##-------------------------------------------------------------------------
## Utility functions
##-------------------------------------------------------------------------

def all_assets_flattened(groups):
    """Flatten all assets contained in groups into a single list.
    """
    all_assets = [g.assets for g in groups]
    return [asset for assets in all_assets for asset in assets]


def parse_property(s, delim='='):
    """Parse a string `s' into a property pair (Assuming s has already been
    checked or known to contain an instance of `delim'. Whitespace and
    unnecessary characters (including comment prefix) will be dropped
    from both key and value and a tuple of (key, value) will be
    returned.
    """
    parts = s.split(delim)
    key = parts[0].replace('#','').strip()
    val = parts[1].split('#')[0].strip().replace('"','').replace(',','')

    return (key,val)


def sha1sum(filename):
    """Use hashlib to calculate the SHA1 for contents of given file which
    should already have been checked for existence and read permissions.
    """
    BUF_SIZE = 65536
    sha = sha1()
    with open(filename, 'rb') as file_in:
        while True:
            data = file_in.read(BUF_SIZE)
            if not data:
                break
            sha.update(data)
    return sha.hexdigest()


def file_walk(pathname, matcher):
    """Recursively walk a directory and return any files which satisfy the
    matcher regex. Wraps 'os.walk()' but produces a flat list of
    filenames.
    """
    # Pre-compile matcher expression.
    regex = re.compile(matcher)

    filenames = []
    for root,dname,fnames in walk(pathname):
        for f in fnames:
            filenames.append(path.join(root, f))

    return [f for f in filenames if regex.match(f)]


##-------------------------------------------------------------------------
## Main
##-------------------------------------------------------------------------

def main(args):
    global debug, verbose

    debug = args.debug
    verbose = args.verbose
    asset_filter = args.filter or default_filter

    # Check that we're in the root of the gerrit project to run this
    # script.
    cwd = getcwd()
    jgit_bzl = path.join(cwd, "lib", "jgit", "jgit.bzl")
    workspace = path.join(cwd, "WORKSPACE")

    if not path.isfile(workspace) or not path.isfile(jgit_bzl):
        print("Current directory does not appear to be gerrit root.", file=stderr)
        exit(1)

    # Parse assets from the jgit.bzl and workspace files. In future we
    # can add additional build files to this list or even expose the
    # creation of this list as an argument.
    # Any actions taken by the script will apply to all members of this
    # list, e.g. patching shas or validation:
    build_file_assets = [
        assets_from_bazel_file(jgit_bzl),
        assets_from_bazel_file(workspace)
    ]

    for asset in build_file_assets:
        asset.filter_assets(asset_filter)
        if debug:
            asset.print_contents_summary()

    # If '-c' just check contents of bazel files and return before any of
    # the patching functionality. Errors should be resolved before using
    # the script:
    if args.check:
        issues = 0
        for asset in build_file_assets:
            issues += asset.print_validation_summary()
        exit(issues)

    # Get a flattened list of all assets from the build files here to
    # check if we need to proceed. We also pass this as the reference
    # set if we build the jar store assets from a repository.
    all_assets = all_assets_flattened(build_file_assets)

    if len(all_assets) == 0:
        print("No assets in build set. Filter was: {0}".format(asset_filter), file=stderr)
        print("Check that filter is a regular expression.", file=stderr)
        exit(1)

    # Now we want to look at the jars in the location we have specified (or default
    # to the repository specified by each asset and load an asset group.
    # The jar store should be one of:
    #    Unspecified  In the default behaviour of the user not specifying a jar
    #                 location, we will use the repository field on each asset. If
    #                 an asset does not specify a repository it will be skipped.
    #                 (TODO: Revise this behaviour? E.g. Do we want to default?)
    #  - A directory  in which case we'll do a recursive walk looking for jars whose
    #                 name matches our filter expression.
    #  - A repo name  Which should be defined in our known repos map above, or if it
    #                 ends in a ':' character we assume the user supplied a literal
    #                 value.
    #  - A file       Which will be treated as another bazel file. Probably not a
    #                 common usage, but supported.
    if not args.jar_store:
        jar_assets = assets_from_default_repository(all_assets)
    elif path.isfile(args.jar_store):
        jar_assets = assets_from_bazel_file(args.jar_store)
    elif path.isdir(args.jar_store):
        jar_assets = assets_from_directory_walk(args.jar_store)
    else:
        # If jar_store is neither a file nor directory on disk we try to resolve
        # as a repository name or prefix.
        jar_assets = assets_from_repository(args.jar_store, all_assets)

    jar_assets.filter_assets(asset_filter)

    if debug:
        jar_assets.print_contents_summary()

    # If '-p' do our patching back into bazel:
    if args.patch:
        for asset in build_file_assets:
            replace_shas_in_file(asset, jar_assets, args.enable)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="""
    This script will automatically update the asset SHAs in bazel/build
    files by checking latest local builds or repositories for reference
    assets and computing the new SHA.
    """)

    parser.add_argument('-c','--check', action='store_true',
                        help="Only validate assets in bazel files.")
    parser.add_argument('-e','--enable', action='store_true',
                        help="If a SHA being patched is commented out, also uncomment it.")
    parser.add_argument('-f','--filter',
                        help="""Specifiy a custom filter for assets. This regex will match against
                        the artifact coordinate to restrict assets in
                        both build files and reference sets. This should
                        be a valid python regex captured in single
                        quotes if it contains the wildcard '*'
                        character. The default value is '{0}'""".format(default_filter))
    parser.add_argument('-j','--jar_store',
                        help="""Specify an override jar source for reference assets.
                        This can either be a directory containing jars, a repository prefix,
                        or one of the known repo names: """ + ','.join(known_repos.keys()))
    parser.add_argument('-p','--patch', action='store_true',
                        help="Automatically patch bazel build files with SHAs from reference assets.")
    parser.add_argument('-v','--verbose', action='store_true', help="Enable verbose logging")
    parser.add_argument('-x','--debug', action='store_true', help="Enable full debug logging")
    args = parser.parse_args()

    main(args)
