#!/bin/bash --noprofile

declare SCRIPT_DIR=`dirname "$BASH_SOURCE"`
declare GERRIT_REPO_ROOT=$(realpath "$SCRIPT_DIR/../")
declare FINAL_ASSETS_PATH="$GERRIT_REPO_ROOT/bazel-bin"
declare ENV_PROPERTIES_FILE="$GERRIT_REPO_ROOT/env.properties"
declare -r TRUE="true"
declare -r FALSE="false"

declare ASSETS_FOUND=""

# Function checks to see if a file (asset) exists and if it does it records it in the list
# of items to deploy.
# Parameters:
#
# assetLocation=<string>
#   Location to the asset to be checked.
# isRequiredAsset=<boolean:true>
#   Indicates whether the item is required and fails the process if it doesn't exist.
function check_for_asset(){
  local assetLocation=$1
  local isRequiredAsset=${2:-$FALSE}

  if [[ -f "$assetLocation" ]]; then
    echo "Found asset: $assetLocation"
    ASSETS_FOUND="$ASSETS_FOUND $assetLocation"
  else
	  echo "Unable to locate asset: $assetLocation"

	  # We only need to exit here hard, if the isRequiredAsset is set to true.
	  if [[ $isRequiredAsset == $TRUE ]]; then
	    echo "Item was a required asset - exiting now."
	    exit 1;
	  fi
  fi
}

check_for_asset "$FINAL_ASSETS_PATH/plugins/delete-project/delete-project.jar" $TRUE
# TODO: for moment during 2.16 dev lfs is optional, put back to required.
check_for_asset "$FINAL_ASSETS_PATH/plugins/lfs/lfs.jar" $FALSE
check_for_asset "$FINAL_ASSETS_PATH/release.war" $TRUE
check_for_asset "$FINAL_ASSETS_PATH/gerrit-console-api/console-api.jar" $TRUE
check_for_asset "$GERRIT_REPO_ROOT/target/gerritms-installer.sh" $TRUE

# Export all the items / assets now.
export ASSETS_FOUND
echo "ASSETS_FOUND: $ASSETS_FOUND"

# Finally write the items to be deployed into the environment information to be supplied
# out of this shell and to the artifactory plugin.
echo "ASSETS_FOUND=$ASSETS_FOUND" >> $ENV_PROPERTIES_FILE
