#!/bin/bash --noprofile

build_tool_dir="$(dirname "$0")"
source "$build_tool_dir/build-tools-base.sh"

declare -r TRUE="true"
declare -r FALSE="false"
declare WORKSPACE_PATH_LEN
declare ASSETS_FOUND=""

# Obtain supplied args.
#  list_assets_locations.sh <workspace> <debug_enabled>
#  e.g. list_assets_locations.sh /path/xyz true

declare WORKSPACE_PATH=${1}
declare DEBUG_ENABLED=${2:$TRUE}

# Only output message if DEBUG_ENABLED flag is true.
function debug(){
  local msg=$1
  if [[ -z $DEBUG_ENABLED ]]; then
    echo "$msg"
  elif [[ "$DEBUG_ENABLED" == "$TRUE" ]]; then
    echo "$msg"
  fi
}

if [[ -z $WORKSPACE_PATH ]]; then
  # default to be gerrit repo root for the workspace location.
  debug "Defaulting workspace to gerrit repository root."
  WORKSPACE_PATH=$GERRIT_REPO_ROOT
fi

WORKSPACE_PATH_LEN=${#WORKSPACE_PATH}
debug "WORKSPACE_PATH: $WORKSPACE_PATH"

# Function to simply add the asset to a list of assets for jenkins to consume.
# Currently it requires a command seperated list.
function addAssetToList() {
  debug "adding asset relative path: $suffixLocation"
  if [[ -z $ASSETS_FOUND ]]; then
    ASSETS_FOUND="$suffixLocation"
  else
    ASSETS_FOUND="$ASSETS_FOUND,$suffixLocation"
  fi
}

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
    debug "Found asset: $assetLocation"

  # Trim off gerrit_root, as the plugins only use relative paths.
    local suffixLocation=${assetLocation:$WORKSPACE_PATH_LEN+1}
    addAssetToList "$suffixLocation"
  else
    debug "Unable to locate asset: $assetLocation"

    # We only need to exit here hard, if the isRequiredAsset is set to true.
    if [[ $isRequiredAsset == $TRUE ]]; then
      echo "Item was a required asset - exiting now."
      exit 1;
    fi
  fi
}

check_for_asset "$ASSETS_PATH/plugins/gerrit-delete-project-plugin/gerrit-delete-project-plugin.jar" $TRUE
check_for_asset "$ASSETS_PATH/plugins/lfs/lfs.jar" $TRUE
check_for_asset "$ASSETS_PATH/release/release.war" $TRUE
check_for_asset "$ASSETS_PATH/gerritconsoleapi/console-api.jar" $TRUE
check_for_asset "$GERRIT_REPO_ROOT/target/gerritms-installer.sh" $TRUE

check_for_asset "$ASSETS_PATH/gerrit-extension-api/extension-api.jar" $TRUE
check_for_asset "$ASSETS_PATH/gerrit-plugin-api/plugin-api.jar" $TRUE

# TODO: Push java doc and src assets
check_for_asset "$ASSETS_PATH/gerrit-extension-api/extension-api-javadoc/extension-api-javadoc.jar" $TRUE
check_for_asset "$ASSETS_PATH/gerrit-plugin-api/plugin-api-src.jar" $TRUE
check_for_asset "$ASSETS_PATH/gerrit-plugin-api/plugin-api-javadoc/plugin-api-javadoc.jar" $TRUE


# Export all the items / assets now.
export ASSETS_FOUND
debug "Recorded all ASSETS_FOUND: $ASSETS_FOUND"

# Finally write the items to be deployed into the environment information to be supplied
# out of this shell and to the artifactory plugin.
echo "$ASSETS_FOUND"

# before we write out the new assets found value, because we append to other build info, so support running this more than
# once say after building gerrit on its own, and then later after you build the plugins, we support removal and reinserting
# the asests found.

# Remove any existing entry.
sed -i 's/ASSETS_FOUND=.*//' $ENV_PROPERTIES_FILE

echo "ASSETS_FOUND=$ASSETS_FOUND" >> $ENV_PROPERTIES_FILE
