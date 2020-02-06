#!/bin/bash --noprofile

build_tool_dir="$(dirname "$0")"
source "$build_tool_dir/build-tools-base.sh"

declare GERRIT_BAZEL_OUT=$GERRIT_REPO_ROOT/bazel-bin
declare GERRITMS_INSTALLER_OUT=$GERRIT_REPO_ROOT/target

#Usage:
#  ./deploy-gerrit-assets.sh <version> <artifact_repo> <repository_id>
#  Common two use cases will be:
#  deploy-gerrit-assets.sh 2.16.12_WDv1-SNAPSHOT libs-release-local releases
#  deploy-gerrit-assets.sh 2.16.12_WDv1-SNAPSHOT libs-staging-local artifacts

# Obtaining script arguments
declare VERSION=${1}
declare ARTIFACT_REPO=${2}
declare REPOSITORY_ID=${3}

if [[ -z "$VERSION" ]]; then
  echo "ERROR: Required script parameter missing. Version is not set"
  exit 1
fi

if [[ -z "$ARTIFACT_REPO" ]]; then
  echo "ERROR: Required script parameter missing. Artifact Repo is not set"
  exit 1
fi

if [[ -z "$REPOSITORY_ID" ]]; then
  echo "ERROR: Required script parameter missing. RepositoryId is not set";
  exit 1
fi

declare -r URL="http://artifacts.wandisco.com:8081/artifactory"
declare -r GERRITMS_GROUPID="com.google.gerrit"
ALL_ASSETS="$(tail -n 1 $ENV_PROPERTIES_FILE)"


#Read in the found assets as an array and call the mvn upload function with the
#required arguments for each found assets
function process_gerritms_assets(){
    IFS=', ' read -r -a asset_array <<< "$ALL_ASSETS";

    for asset in "${asset_array[@]}"
    do
        #trimming the assets name from the path
        artifact="$(basename $asset)"
        case "$artifact" in
        'release.war') mvn_upload $GERRIT_BAZEL_OUT/$artifact gerritms war
            ;;
        'delete-project.jar') mvn_upload $GERRIT_BAZEL_OUT/plugins/delete-project/$artifact delete-project-plugin jar
            ;;
        'lfs.jar') mvn_upload $GERRIT_BAZEL_OUT/plugins/lfs/$artifact lfs-plugin jar
            ;;
        'gerritms-installer.sh') mvn_upload $GERRITMS_INSTALLER_OUT/$artifact gerritms-installer
            ;;
        'console-api.jar') mvn_upload $GERRIT_BAZEL_OUT/gerrit-console-api/$artifact gerrit-console-api jar
            ;;
        *) echo "ERROR: Unknown asset $artifact, not performing the mvn deploy"
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

    # PACKAGING is optional and if it is set then we have two different ways of
    # deploying depending on if it is set or not.
    if [[ -n "$PACKAGING" ]]; then
        # deploy:deploy-file is used to install a single artifact along with its pom.
        mvn -X deploy:deploy-file -DgroupId="$GERRITMS_GROUPID" -DartifactId="$ARTIFACT_ID" -Dversion="$VERSION" -Dpackaging="$PACKAGING" \
            -Dfile="$DEPLOY_FILE" -DrepositoryId="$REPOSITORY_ID" -Durl="$URL/$ARTIFACT_REPO"
    else
        # Deployment of plain files that do not have a supported packaging type.
        mvn -X deploy:deploy-file -DgroupId="$GERRITMS_GROUPID" -DartifactId="$ARTIFACT_ID" -Dversion="$VERSION" \
            -Dfile="$DEPLOY_FILE" -DrepositoryId="$REPOSITORY_ID" -Durl="$URL/$ARTIFACT_REPO"
    fi
}

process_gerritms_assets
