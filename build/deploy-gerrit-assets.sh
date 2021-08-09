#!/bin/bash --noprofile

build_tool_dir="$(dirname "$0")"
source "$build_tool_dir/build-tools-base.sh"

#Usage:
#  ./deploy-gerrit-assets.sh <version> <artifact_repo> <repository_id>
#  Common two use cases will be:
#  deploy-gerrit-assets.sh 2.16.12_WDv1-SNAPSHOT libs-release-local releases /dir/to/gerrit_repo
#  deploy-gerrit-assets.sh 2.16.12_WDv1-SNAPSHOT libs-staging-local artifacts /dir/to/gerrit_repo

# Obtaining script arguments
declare VERSION=${1}
declare ARTIFACT_REPO=${2}
declare REPOSITORY_ID=${3}
# we could use gerrit_repo_root but for consistency using JENKINS_WORKSPACE and passing it in here same
# as list_asset_locations arg, so they always match.
declare WORKSPACE_PATH=${4}


if [[ -z "$VERSION" ]]; then
  echo "ERROR: Required script parameter missing. Version is not set, arg 1."
  exit 1
fi

if [[ -z "$ARTIFACT_REPO" ]]; then
  echo "ERROR: Required script parameter missing. Artifact Repo is not set, arg 2"
  exit 1
fi

if [[ -z "$REPOSITORY_ID" ]]; then
  echo "ERROR: Required script parameter missing. RepositoryId is not set, arg 3.";
  exit 1
fi

if [[ -z "$WORKSPACE_PATH" ]]; then
  echo "ERROR: Required script parameter missing. Workspace path is not passed as arg 4.";
  exit 1
fi



if [[ ! -f "$ENV_PROPERTIES_FILE" ]]; then
  echo "ERROR: Unable to find the ENV_PROPERTIES_FILE with asset information to be deployed."
  exit 1;
fi

declare -r URL="http://artifacts.wandisco.com:8081/artifactory"
declare -r GERRITMS_GROUPID="com.google.gerrit"
# get assets found out of the env file.
ALL_ASSETS="$(sed -n '/ASSETS_FOUND=.*/p' $ENV_PROPERTIES_FILE)"
# Trim assets_found= off the start, to leave us with the data.
ASSETS_LIST=$(echo "$ALL_ASSETS" | grep -oP "^ASSETS_FOUND=\K.*")

echo "Assets to be deployed are: $ASSETS_LIST"

#Read in the found assets as an array and call the mvn upload function with the
#required arguments for each found assets
function process_gerritms_assets(){
    IFS=', ' read -r -a asset_array <<< "$ASSETS_LIST";

    for asset in "${asset_array[@]}"
    do
        #trimming the assets name from the path
        artifact="$(basename $asset)"
        echo "Processing artifact: $artifact at asset path: $asset"

        case "$artifact" in
        'release.war') mvn_upload $WORKSPACE_PATH/$asset gerritms war || exit 1;
            ;;
        'delete-project.jar') mvn_upload $WORKSPACE_PATH/$asset delete-project-plugin jar  || exit 1;
            ;;
        'gerrit-delete-project-plugin.jar') mvn_upload $WORKSPACE_PATH/$asset delete-project-plugin jar  || exit 1;
            ;;
        'lfs.jar') mvn_upload $WORKSPACE_PATH/$asset lfs-plugin jar  || exit 1;
            ;;
        'gerritms-installer.sh') mvn_upload $WORKSPACE_PATH/$asset gerritms-installer  || exit 1;
            ;;
        'console-api.jar') mvn_upload $WORKSPACE_PATH/$asset gerrit-console-api jar || exit 1
            ;;
        'extension-api.jar') mvn_upload $WORKSPACE_PATH/$asset gerrit-extension-api jar  || exit 1;
            ;;
        'plugin-api.jar') mvn_upload $WORKSPACE_PATH/$asset gerrit-plugin-api jar  || exit 1;
            ;;
        *) echo "WARNING: Unknown or unsupported asset $artifact, not performing the mvn deploy. NB we dont upload javadoc or srcs yet.";
            ;;
        esac
    done
}


# This function takes three arguments, the file to deploy, the artifactId that defines where the asset should
# be deployed to in artifactory and the packaging type (typically war or jar). If not packaging type is specified then
# we are assuming it is a file type which doesn't have a supported packing type, eg. the gerritms-installer.sh which is a bash
# script.
function mvn_upload(){
    DEPLOY_FILE="$1"
    ARTIFACT_ID="$2"
    PACKAGING="$3"

    if [[ -z "$DEPLOY_FILE" ]]; then
      echo "ERROR: File to deploy has not been supplied."
    fi

    if [[ -z "$ARTIFACT_ID" ]]; then
      echo "ERROR: ArtifactId for the asset to deploy has not been supplied."
    fi

    echo "mvn_upload: requested on artifactId: $ARTIFACT_ID packaging: $PACKAGING file: $DEPLOY_FILE"

    # PACKAGING is optional and if it is set then we have two different ways of
    # deploying depending on if it is set or not.
    if [[ -n "$PACKAGING" ]]; then
        # deploy:deploy-file is used to install a single artifact along with its pom.
        mvn -X deploy:deploy-file -DgroupId="$GERRITMS_GROUPID" -DartifactId="$ARTIFACT_ID" -Dversion="$VERSION" -Dpackaging="$PACKAGING" \
            -Dfile="$DEPLOY_FILE" -DrepositoryId="$REPOSITORY_ID" -Durl="$URL/$ARTIFACT_REPO" || (
            echo "Failed deploying file - bailing out." && exit 1;
            )
    else
        # Deployment of plain files that do not have a supported packaging type.
        mvn -X deploy:deploy-file -DgroupId="$GERRITMS_GROUPID" -DartifactId="$ARTIFACT_ID" -Dversion="$VERSION" \
            -Dfile="$DEPLOY_FILE" -DrepositoryId="$REPOSITORY_ID" -Durl="$URL/$ARTIFACT_REPO" || (
            echo "Failed deploying file - bailing out." && exit 1;
            )
    fi
}

process_gerritms_assets
