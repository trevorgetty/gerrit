#!/bin/bash --noprofile

# This is slightly different from get_version_number.sh which reads a static version.bzl file for version info.
# Instead I want the same version info that is a) build into the war file b) changes automatically with each commit

# version: version-from-last-annotated-tag
# numcommits: number of commits since annotated tag.
# sha: current commit sha this is built from
# dirty: indicator if this commit does not have the annoated tag, so its not a release build
#
# format: <version>-<numcommits>-<sha>[-dirty]
#
# e.g. v2.16.11-RP-1.10.0.1-DEV-26-g7dbf4f05eb-dirty

export GERRIT_WORKING_VERSION=$(make display_version | grep STABLE_BUILD_GERRIT_LABEL | cut -d ' ' -f 2)

if [[ -z $GERRIT_WORKING_VERSION ]]; then
  echo "Something wrong with working version command."
  exit 1;
fi

echo "GERRIT_WORKING_VERSION"
echo "GERRIT_WORKING_VERSION=$GERRIT_WORKING_VERSION" >> $ENV_PROPERTIES_FILE
