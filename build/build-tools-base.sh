#!/bin/bash --noprofile
#Common variables and functions that can be imported by other scripts

declare SCRIPT_DIR=`dirname "$BASH_SOURCE"`
declare GERRIT_REPO_ROOT=$(realpath "$SCRIPT_DIR/../")
declare ASSETS_PATH="$GERRIT_REPO_ROOT/buck-out/gen"
declare ENV_PROPERTIES_FILE="$GERRIT_REPO_ROOT/env.properties"

