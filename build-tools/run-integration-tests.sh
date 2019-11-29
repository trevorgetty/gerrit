#!/usr/bin/env bash

# If the release war path, console api path aren't exported env variables, set to args if they have been passed in.
[[ -z $RELEASE_WAR_PATH ]] && RELEASE_WAR_PATH=$1
[[ -z $GERRIT_TEST_LOCATION ]] && GERRIT_TEST_LOCATION=$2
[[ -z $CONSOLE_API_JAR_PATH ]] && CONSOLE_API_JAR_PATH=$3
[[ -z $GITMS_VERSION ]] && GITMS_VERSION=$4


## Unset Jenkins ENV variables for Git commits
unset GIT_AUTHOR_NAME
unset GIT_AUTHOR_EMAIL
unset GIT_AUTHOR_DATE
unset GIT_COMMITTER_NAME
unset GIT_COMMITTER_EMAIL
unset GIT_COMMITTER_DATE
unset EMAIL

echo "**************** Package Versions *****************"
mysql --version
python --version
echo "***************************************************\n\n"

echo "Testing environment was setup as:"
echo "Gerrit test location is: $GERRIT_TEST_LOCATION"
echo "Release war path is: $RELEASE_WAR_PATH"
echo "Console-Api jar path is: $CONSOLE_API_JAR_PATH"
echo "GITMS_VERSION is: $GITMS_VERSION"
echo ""

# check the release war is in the source location
if [ -f $RELEASE_WAR_PATH/release.war ]; then
  echo "Found release.war asset"
else
  echo "Failed to find the source release.war asset" && exit 1;
fi

mkdir -p $GERRIT_TEST_LOCATION

echo "Working out current git user account"

# Take current user, and trim off the @ section which should leave us with current git user to clone as.
tmp_email=$(git config --global --get user.email)

[[ -z $tmp_email ]] && echo "No user.email set for git user account -> default to build.jenkins"
[[ -z $tmp_email ]] && tmp_email=build.jenkins@wandisco.com

echo Git user.email is: $tmp_email

git_username=${tmp_email%@*}
echo Here is the new gituser: $git_username


#Run integration tests with generated Gerrit MS war against latest Git MS
# First check if the testing directory already exists, run git pull instead to update it, as this could be a dev box.
if [ -d $GERRIT_TEST_LOCATION/jgit-update-service/.git ]; then
  # directory is here already, run a pull instead.
  cd $GERRIT_TEST_LOCATION/jgit-update-service
  git pull || {
    echo "Failed to pull the and update GitMS branch"
    exit 1;
  }
else
  # clone a fresh repo
  git clone --branch "$GITMS_VERSION" --depth 1 ssh://$git_username@gerrit-uk.wandisco.com:29418/jgit-update-service $GERRIT_TEST_LOCATION/jgit-update-service || {
    echo "Failed to clone the correct branch of GitMS dictated by GITMS_VERSION: $GITMS_VERSION"
    exit 1;
  }
  cd $GERRIT_TEST_LOCATION/jgit-update-service || {
    echo "Failed to change location to the GitMS test site."
    exit 1;
  }
fi

# Copy over the gerritMS build assets.  Really we should use the real installer package, but this is what it uses
# for now.....
cp -a "$RELEASE_WAR_PATH/release.war" "$GERRIT_TEST_LOCATION/jgit-update-service/gerrit.war" || {
  echo "Failed to copy release.war - exiting."
  exit 1;
}
cp -a "$CONSOLE_API_JAR_PATH/console-api.jar" "$GERRIT_TEST_LOCATION/jgit-update-service/console-api.jar" || {
  echo "Failed to copy console-api - exiting."
  exit 1;
}

#MySQL Setup
echo "About to update the local mysql instance, to allow our testing user access"

mysql -uroot -e "CREATE USER 'jenkins'@'%' IDENTIFIED BY 'password';" || true
mysql -uroot -e "CREATE USER 'jenkins'@'localhost' IDENTIFIED BY 'password';" || true

echo "Testing environment cloned, and configured -> start test run."

# Move to the testing directory and start using its commands.
cd $GERRIT_TEST_LOCATION/jgit-update-service || {
  echo "Failed to move into test location."
  exit 1;
}

make clean fast-assembly

#echo "Starting integration tests"
DELAY=60 ./test-rb/run/gerrit/default.sh
