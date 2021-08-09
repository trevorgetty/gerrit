######################################################################
# Makefile for gerrit main war file, and then the installer package.
#
# All build work is done by BUCK.
#
# To make the installer package run "make installer"
#
# To build verything run "make all"
#   N.B. this will run tests also.
#
# To build everything but without tests run "make all-skip-tests"
#
######################################################################

# Work out this make files directory and the current PWD seperately
#mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
mkfile_path := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
current_dir := $PWD

# Gerrit repo root can be set to the mkfile path location
GERRIT_ROOT= $(mkfile_path)

BUILD_TOOLS := $(GERRIT_ROOT)/build

# JENKINS_WORKSPACE is the location where the job puts work by default, and we need to have assets paths relative
# to the workspace in some occasions.
JENKINS_WORKSPACE ?= $(GERRIT_ROOT)

# Works on OSX.
VERSION := $(shell $(GERRIT_ROOT)/build/get_version_number.sh $(GERRIT_ROOT))
WORKING_VERSION=$(shell $(GERRIT_ROOT)/build/get_gerrit_working_version.sh)

GITMS_VERSION := GITMS_VERSION
GERRIT_BUCK_OUT := $(GERRIT_ROOT)/buck-out
RELEASE_WAR_PATH := $(GERRIT_BUCK_OUT)/gen/release
CONSOLE_API_JAR_PATH := $(GERRIT_BUCK_OUT)/gen/gerritconsoleapi

CONSOLE_ARTIFACTID   := gerrit-console-api
CONSOLE_GROUPID := com.google.gerrit
CONSOLE_PREFIX := $(CONSOLE_GROUPID).$(CONSOLE_ARTIFACTID)

PKG_NAME   := gerritms
RPM_PREFIX := com.google.gerrit
PREFIX := $(RPM_PREFIX)/$(PKG_NAME)

# By default do not skip unit tests.
SKIP_TESTS :=

# maybe use /var/lib/jenkins/tmp it can still be ok on our local machines, maybe make switchable to /tmp but its ok for now.
JENKINS_DIRECTORY := /var/lib/jenkins
JENKINS_TMP_TEST_LOCATION := $(JENKINS_DIRECTORY)/tmp
DEV_BOX_TMP_TEST_LOCATION := /tmp/builds/gerritms
GERRIT_TEST_LOCATION=$(JENKINS_TMP_TEST_LOCATION)
BUILD_USER=$USER
git_username=Testme

# Deployment info
# Allow customers to pass in their own test or local artifactory for deployment
ARTIFACTORY_SERVER ?= http://artifacts.wandisco.com:8081/artifactory
# Also allow us to control which repository to deploy to - e.g. libs-release / local testing repo.
ARTIFACT_REPO ?= libs-staging-local
ARTIFACTORY_DESTINATION := $(ARTIFACTORY_SERVER)/$(ARTIFACT_REPO)

all: display_version fast-assembly installer run-integration-tests

all-skip-tests: display_version fast-assembly installer skip-tests

check_gitms_version:
#check GITMS_VERSION prop has value GITMS_VERSION or empty - either is bad.
	$(if $(GITMS_VERSION),,$(error GITMS_VERSION is not set))

ifeq ($(GITMS_VERSION), GITMS_VERSION)
	@echo "No valid GITMS_VERSION info given.."
	exit 1;
endif

.PHONY: check_gitms_version

display_version:
	@echo "About to use the following version information from the GIT Working data, not the VERSION file."
	@python tools/workspace_status.py
.PHONY: display_version

display_gerrit_working_version:
	@# Note this is based on the tag information in the git repo, along with commit sha and dirty indicator
	@# e.g. 2.13.12-RP-1.9.7.3-2342323423-dirty or when on a clean tag for release # e.g. 2.13.12-RP-1.9.7.3
	@echo "Gerrit working version: $(WORKING_VERSION)"

.PHONY: display_gerrit_working_version
#
# Do an assembly without doing unit tests, of all our builds
#
fast-assembly: fast-assembly-gerrit fast-assembly-console fast-assembly-api
.PHONY: fast-assembly

#
# Build just gerritMS
#
fast-assembly-gerrit:

	@echo "\n************ Compile Gerrit Starting **************"
	@echo "Building GerritMS"
	buck build release
	@echo "\n************ Compile Gerrit Finished **************"
.PHONY: fast-assembly-gerrit

#
# Build just the console-api
#
fast-assembly-console:
	@echo "\n************ Compile Console-API Starting **************"
	@echo "Building console-api"
	buck build //gerritconsoleapi:console-api
	@echo "\n************ Compile Console-API Finished **************"
.PHONY: fast-assembly-console
#
# Build just the api for extensibility
#
fast-assembly-api:
	@echo "\n************ Compile Ext API Starting **************"
	@echo "Building extensibility api"
	buck build api
	@echo "\n************ Compile Ext API Finished **************"
.PHONY: fast-assembly-api



clean: | $(JENKINS_DIRECTORY)
	@echo "\n************ Clean Phase Starting **************"
	buck clean
	rm -rf $(GERRIT_BUCK_OUT)
	rm -rf $(GERRIT_TEST_LOCATION)/jgit-update-service
	rm -f $(GERRIT_ROOT)/env.properties
	@echo "\n************ Clean Phase Finished **************"
.PHONY: clean

check_build_assets:
	# check that our release.war and console-api.jar items have been built and are available
	$(eval RELEASE_WAR_PATH=$(RELEASE_WAR_PATH))
	$(eval CONSOLE_API_JAR_PATH=$(CONSOLE_API_JAR_PATH))

	# Writing out a new file, so create new one.
	@echo "RELEASE_WAR_PATH=$(RELEASE_WAR_PATH)" > "$(GERRIT_ROOT)/env.properties"
	@echo "INSTALLER_PATH=target" >> $(GERRIT_ROOT)/env.properties
	@echo "CONSOLE_API_JAR_PATH=$(CONSOLE_API_JAR_PATH)" >> $(GERRIT_ROOT)/env.properties
	@echo "Env.properties is saved to: $(GERRIT_ROOT)/env.properties)"

	@[ -f $(RELEASE_WAR_PATH)/release.war ] && echo release.war exists || ( echo releaes.war not exists && exit 1;)
	@[ -f $(CONSOLE_API_JAR_PATH)/console-api.jar ] && echo console-api.jar exists || ( echo console-api.jar not exists && exit 1;)
.PHONY: check_build_assets


installer: check_build_assets
	@echo "\n************ Installer Phase Starting **************"

	@echo "Building Gerrit Installer..."
	$(GERRIT_ROOT)/gerrit-installer/create_installer.sh $(RELEASE_WAR_PATH)/release.war $(CONSOLE_API_JAR_PATH)/console-api.jar

	@echo "\n************ Installer Phase Finished **************"
.PHONY: installer


skip-tests:
	@echo "Skipping integration tests."
.PHONY: skip-tests

# Target used to check if the jenkins tmp directory exists, and if not to use
# /tmp on a users dev box.
$(JENKINS_DIRECTORY):

	@echo "Jenkins directory does not exist, fallback to /tmp on dev boxes"
	$(eval GERRIT_TEST_LOCATION = $(DEV_BOX_TMP_TEST_LOCATION))

	@echo Test directory is now: $(GERRIT_TEST_LOCATION)

	mkdir -p $(GERRIT_TEST_LOCATION)

run-integration-tests: check_gitms_version check_build_assets | $(JENKINS_DIRECTORY)
	@echo "\n************ Integration Tests Starting **************"
	@echo "About to run integration tests -> resetting environment"

	@echo "Release war path in makefile is: $(RELEASE_WAR_PATH)"

	@[ -z $(RELEASE_WAR_PATH)/release.war ] && echo release.war exists || ( echo releaes.war not exists && exit 1;)

	./build/run-integration-tests.sh $(RELEASE_WAR_PATH) $(GERRIT_TEST_LOCATION) $(CONSOLE_API_JAR_PATH) $(GITMS_VERSION)

	@echo "\n************ Integration Tests Finished **************"
.PHONY: run-integration-tests

deploy: deploy-all-gerrit
.PHONY: deploy

# Deploys all gerritms assets
deploy-all-gerrit:
	@echo "\n************ Deploying All GerritMS Assets **************"
	@echo "All GerritMS assets will be deployed."
	@echo "[ release.war, gerritms-installer.sh, lfs.jar, delete-project.jar, console-api.jar ]"
	@echo "Checking for available built assets"
	@echo "***********************************************************"
	$(BUILD_TOOLS)/list_asset_locations.sh $(JENKINS_WORKSPACE) true
	@echo "***********************************************************"
	@echo "Beginning deployment of all assets, note using working_version: $(WORKING_VERSION) and not local version: $(VERSION)"
	# NOTE that the repositoryId is currently 'artifacts' to cover libs-staging.
	# There will be a check in future whereby we may want to deploy to a different repo e.g. libs-release-local \
	# in which case the repositoryId would need to be changed on the fly to: 'releases'
	$(BUILD_TOOLS)/deploy-gerrit-assets.sh $(WORKING_VERSION) $(ARTIFACT_REPO) artifacts $(JENKINS_WORKSPACE)

.PHONY:deploy-all-gerrit

#Still available to use but considered deprecated in favour of deploy-all-gerrit
deploy-gerrit:
	@echo "\n************ Deploy GerritMS Starting **************"
	@echo "TODO: For now skipping the deploy of GerritMS to artifactory."
	@echo "\n************ Deploy  GerritMS Finished **************"
.PHONY: deploy-gerrit

#Still available to use but considered deprecated in favour of deploy-all-gerrit
deploy-console:
	@echo "\n************ Deploy Console-API Phase Starting **************"
	@echo "Running mvn deploy:deploy-file to deploy the console-api.jar to Artifactory..."
	@echo "Deploying as version: $(VERSION)"

	# use mvn deploy-file target, to deploy any file, and we will give it the pom properties to deploy as...
	mvn deploy:deploy-file \
	-DgroupId=$(CONSOLE_GROUPID) \
	-DartifactId=$(CONSOLE_ARTIFACTID) \
	-Dversion="$(VERSION)" \
	-Dpackaging=jar \
	-Dfile=$(CONSOLE_API_JAR_PATH)/console-api.jar \
	-DrepositoryId=releases \
	-Durl=http://artifacts.wandisco.com:8081/artifactory/libs-release-local
	@echo "\n************ Deploy Console-API Phase Finished **************"
.PHONY: deploy-console

help:
	@echo
	@echo Available popular targets:
	@echo
	@echo "   make all                          -> Will compile all packages, and create installer, and finish with integration tests"
	@echo "   make clean                        -> Will clean out our integration test, package build and tmp build locations."
	@echo "   make clean fast-assembly          -> will compile and build GerritMS without the installer"
	@echo "   make fast-assembly                -> will just build the GerritMS and ConsoleAPI packages"
	@echo "   make fast-assembly-gerrit         -> will just build the GerritMS package"
	@echo "   make fast-assembly-console        -> will just build the GerritMS Console API package"
	@echo "   make fast-assembly-api            -> will just build the GerritMS Extensibility API package used by plugins."
	@echo "   make clean fast-assembly installer  -> will build the packages and installer asset"
	@echo "   make installer                    -> will build the installer asset using already built packages"
	@echo "   make run-integration-tests        -> will run the integration tests, against the already built packages"

	@echo "   make deploy                       -> will deploy all packages including installer of GerritMS, ConsoleAPI and plugins to artifactory."
	@echo "   make deploy-gerrit                -> will deploy the installer package of GerritMS (old deprecated useage.)"
	@echo "   make deploy-console               -> will deploy the installer package of GerritMS Console API (old deprecated useage.)"
	@echo "   make help                         -> Display available targets"

.PHONY: help
