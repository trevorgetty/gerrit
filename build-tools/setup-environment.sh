#!/bin/bash --noprofile

# Some environment information has to be supplied, or worked out..
# 1) Where do we put our alm-dev repo, it should be where our temporary assets are:
# 2) So either GERRIT_TEST_LOCATION is:
#         /var/lib/jenkins/tmp ( on build machine with jenkins )
#         /tmp/builds/gerritms ( on dev machine )
# 3) Finally can overriden by user using GERRIT_TEST_LOCATION=$WORKSPACE which can group assets together in one location
#        $WORKSPACE/gerrit $WORKSPACE/alm-dev $WORKSPACE/jgit-update-service

# maybe use /var/lib/jenkins/tmp it can still be ok on our local machines, maybe make switchable to /tmp but its ok for now.
JENKINS_DIRECTORY=/var/lib/jenkins
JENKINS_TMP_TEST_LOCATION=$JENKINS_DIRECTORY/tmp
DEV_BOX_TMP_TEST_LOCATION=/tmp/builds/gerritms
GERRIT_TEST_LOCATION=${GERRIT_TEST_LOCATION}

if [[ -z $GERRIT_TEST_LOCATION ]]; then
  # Choose fallback, depends on whether jenkins exists and we are on dev machine!
  if [[ -d $JENKINS_TMP_TEST_LOCATION ]]; then
    echo "Using default as Jenkins directory on build machine."
    GERRIT_TEST_LOCATION="$JENKINS_TMP_TEST_LOCATION"
  else
	  echo "Jenkins directory does not exist, fallback to /tmp on dev boxes"
	  GERRIT_TEST_LOCATION="$DEV_BOX_TMP_TEST_LOCATION"
	fi
else
  # Use our supplied variable.
  echo "Using supplied test location."
fi

export GERRIT_TEST_LOCATION;

echo "Temporary assets and testing directory is now: $GERRIT_TEST_LOCATION"
mkdir -p "$GERRIT_TEST_LOCATION"

if [[ ! -d "$GERRIT_TEST_LOCATION/alm-dev" ]]; then

  echo "About to clone alm-dev"

  # Firstly we need to setup the bazel environment for our custom WANDISCO repo.
  # so clone the alm-dev repo, and run the setup script.
  username=$(git config --get user.email | cut -f1 -d"@")

  if [[ -z $username ]]; then
    username="build.jenkins"
  fi

  git clone ssh://$username@gerrit-uk.wandisco.com:29418/gerrit/alm-dev "$GERRIT_TEST_LOCATION/alm-dev" || {
    echo "Failed to clone alm-dev repository - to setup WD customer repos." && exit 1;
  }

fi

$GERRIT_TEST_LOCATION/alm-dev/env-setup/setupGerritWDRepository.sh || {
  echo "Failed to run and setup the Gerrit Bazel Custom Repository information." && exit 1;
}
