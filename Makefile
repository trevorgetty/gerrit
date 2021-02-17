######################################################################
# Makefile for gerrit main war file, and then the installer package.
#
# All build work is done by bazelisk ( a wrapper around bazel version anonymity.
#
# To make the installer package run "make installer"
#
# To build verything run "make all"
#   N.B. this will run tests also.
#
# To build everything but without tests run "make all-skip-tests"
#
######################################################################

SHELL := /bin/bash


# Work out this make files directory and the current PWD seperately
#mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
mkfile_path := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
current_dir := $PWD

# Gerrit repo root can be set to the mkfile path location
GERRIT_ROOT= $(mkfile_path)
GERRIT_BAZELCACHE_PATH := $(shell echo $(realpath $(shell echo ~/.gerritcodereview/bazel-cache)))
GERRIT_BAZEL_BASE_PATH := $(shell bazelisk info output_base 2> /dev/null)

# JENKINS_WORKSPACE is the location where the job puts work by default, and we need to have assets paths relative
# to the workspace in some occasions.
JENKINS_WORKSPACE ?= $(GERRIT_ROOT)

# Allow customers to pass in their own test or local artifactory for deployment
ARTIFACTORY_SERVER ?= http://artifacts.wandisco.com:8081/artifactory

# Also allow us to control which repository to deploy to - e.g. libs-release / local testing repo.
ARTIFACT_REPO ?= libs-staging-local

ARTIFACTORY_DESTINATION := $(ARTIFACTORY_SERVER)/$(ARTIFACT_REPO)

# Works on OSX.
VERSION := $(shell $(GERRIT_ROOT)/build-tools/get_version_number.sh $(GERRIT_ROOT))
GITMS_VERSION := ${GITMS_VERSION}
GERRIT_BAZEL_OUT := $(GERRIT_ROOT)/bazel-bin

GERRITMS_INSTALLER_OUT := target/gerritms-installer.sh
RELEASE_WAR_PATH := $(GERRIT_BAZEL_OUT)/release.war
CONSOLE_API_NAME := console-api
CONSOLE_API_DIR := $(GERRIT_BAZEL_OUT)/gerrit-console-api
# Please note the _deploy jar is the standalone one with the manifest included for running with java -jar xxx.jar
# The console-api.jar without deploy is a version for use in debugging locally with src files included for class path inclusion,
# it can be run using "./console-api" wrapper script.
CONSOLE_API_STANDALONE_JAR_PATH := $(CONSOLE_API_DIR)/$(CONSOLE_API_NAME)_deploy.jar
CONSOLE_API_RELEASE_JAR_PATH := $(CONSOLE_API_DIR)/$(CONSOLE_API_NAME).jar

GERRITMS_GROUPID := com.google.gerrit

CONSOLE_ARTIFACTID := gerrit-console-api
CONSOLE_PREFIX := $(GERRITMS_GROUPID).$(CONSOLE_ARTIFACTID)

GERRITMS_ARTIFACTID := gerritms
GERRITMS_INSTALLER_ARTIFACTID := gerritms-installer

PKG_NAME   := gerritms
RPM_PREFIX := com.google.gerrit
PREFIX := $(RPM_PREFIX)/$(PKG_NAME)

# By default do not skip unit tests.
SKIP_TESTS :=

# maybe use /var/lib/jenkins/tmp it can still be ok on our local machines, maybe make switchable to /tmp but its ok for now.
JENKINS_DIRECTORY := /var/lib/jenkins
JENKINS_TMP_TEST_LOCATION := $(JENKINS_DIRECTORY)/tmp
DEV_BOX_TMP_TEST_LOCATION := /tmp/builds/gerritms

# Gerrit test location can be override by env option or makefile arg, e.g.
# By direct arg:
#		make GERRIT_TEST_LOCATION=/tmp
# By environment:
#		GERRIT_TEST_LOCATION=/tmp2 make

BUILD_USER=$USER
git_username=Testme

all: display_version clean fast-assembly installer run-acceptance-tests
.PHONY:all

all-skip-tests: display_version fast-assembly installer skip-tests
.PHONY:all-skip-tests

display_version:
	@echo "About to use the following version information."
	@python tools/workspace_status.py
.PHONY:display_version

# Do an assembly without doing unit tests, of all our builds
#
fast-assembly: fast-assembly-gerrit fast-assembly-console
	@echo "Finished building assemblies"
.PHONY:fast-assembly
#
# Build just gerritMS
#
fast-assembly-gerrit:
	@echo "\n************ Compile Gerrit Starting **************"
	@echo "Building GerritMS"
	bazelisk build release
	@echo "\n************ Compile Gerrit Finished **************"
.PHONY:fast-assembly-gerrit
#
# Build just the console-api
#
fast-assembly-console:
	@echo "************ Compile Console-API Starting **************"
	@echo "Building console-api and rest of API components"
#	you could call bazelisk build //gerrit-console-api:console-api, or console-api_deploy
# if you are just debugging locally but best to get the full scan of API dependencies build and put into the api.zip.
	bazelisk build api

# In the same way we build release.war and rename later to be gerrit.war we DO NOT overwrite the gerrit.war as this
# is a completely different thing in bazel-bin ( i.e. gerrit without plugins ).  In the same way console-api.jar is a
# version of the console-api for locally debugging which can be run from the console-api script.
	@echo "Ready to pick up your console-api from bazel-bin/gerrit-console-api/console-api_deploy.jar"
	@echo "************ Compile Console-API Finished **************"
.PHONY:fast-assembly-console

clean: | $(testing_location)
	$(if $(GERRIT_BAZELCACHE_PATH),,$(error GERRIT_BAZELCACHE_PATH is not set))

	@echo "************ Clean Phase Starting **************"
	bazelisk clean
	rm -rf $(GERRIT_BAZEL_OUT)
	rm -f $(GERRIT_ROOT)/env.properties

	@# Clear jgit artifacts from test location, if known.
	$(if $(GERRIT_TEST_LOCATION), \
        rm -rf $(GERRIT_TEST_LOCATION)/jgit-update-service, \
        @echo "GERRIT_TEST_LOCATION not set, skipping.")

	@# we should think about only doing this with a force flag, but for now always wipe the cache - only way to be sure!!
	@echo cache located here: $(GERRIT_BAZELCACHE_PATH)

	@# Going to clear out anything that looks like our known assets for now...!
	@echo
	@echo "Deleting JGit cached assets.."
	@ls $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*jgit* || echo "Can't find downloaded-artifacts/*jgit*, maybe assets already deleted?"
	@rm -rf $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*jgit*
	@echo
	@echo "Deleting Gerrit-GitMS-Interface cached assets..."
	@ls $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*gitms* || echo "Can't find downloaded-artifacts/*gitms*, maybe assets already deleted?"
	@rm -rf $(GERRIT_BAZELCACHE_PATH)/downloaded-artifacts/*gitms*

	@echo "************ Clean Phase Finished **************"

.PHONY:clean

nuclear-clean: clean
	$(if $(GERRIT_BAZEL_BASE_PATH),,$(error GERRIT_BAZEL_BASE_PATH is not set))

	@echo "******** !! Nuclear Clean Starting !! **********"
	@echo Bazel output base path located here: $(GERRIT_BAZEL_BASE_PATH)

	@# Clear bazel base output directory containing linked bazel-(out|bin|gerrit):
	@echo "Deleting Bazel output base path..."
	@rm -rf $(GERRIT_BAZEL_BASE_PATH)
	@echo "******** !! Nuclear Clean Finished !! **********"

.PHONY:nuclear-clean

list-assets:
	@echo "************ List Assets Starting **************"
	@echo  "Jenkins workspace is: $(JENKINS_WORKSPACE)"

	./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) true

	#$(eval ASSETS_FOUND=$(./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) "false"))
	#@echo "ASSETS_FOUND: $(ASSETS_FOUND)"

	@echo "************ List Assets Finished **************"
.PHONY:list-assets

setup_environment: | $(testing_location)

	@echo "\n************ Setup environment - starting *********"
	@echo "Running environmental scripts from: $(GERRIT_TEST_LOCATION)"

	$(if $(GERRIT_TEST_LOCATION),,$(error GERRIT_TEST_LOCATION is not set))

	$(GERRIT_ROOT)/build-tools/setup-environment.sh

	@echo "\n************ Setup environment - finished *********"

.PHONY:setup_environment


check_build_assets:
	# check that our release.war and console-api.jar items have been built and are available
	$(eval RELEASE_WAR_PATH=$(RELEASE_WAR_PATH))
	$(eval CONSOLE_API_RELEASE_JAR_PATH=$(CONSOLE_API_RELEASE_JAR_PATH))
	$(eval CONSOLE_API_STANDALONE_JAR_PATH=$(CONSOLE_API_STANDALONE_JAR_PATH))

	# Writing out a new file, so create new one.
	@echo "RELEASE_WAR_PATH=$(RELEASE_WAR_PATH)" > "$(GERRIT_ROOT)/env.properties"
	@echo "INSTALLER_PATH=target" >> $(GERRIT_ROOT)/env.properties
	@echo "CONSOLE_API_STANDALONE_JAR_PATH=$(CONSOLE_API_STANDALONE_JAR_PATH)" >> $(GERRIT_ROOT)/env.properties
	@echo "Env.properties is saved to: $(GERRIT_ROOT)/env.properties)"

	@[ -f $(RELEASE_WAR_PATH) ] && echo release.war exists || ( echo release.war not exists && exit 1;)
	@[ -f $(CONSOLE_API_STANDALONE_JAR_PATH) ] && echo console-api exeutable exists || ( echo console-api exeutable does not exist && exit 1;)
.PHONY:check_build_assets

installer: check_build_assets
	@echo "\n************ Installer Phase Starting **************"

	@echo "Building Gerrit Installer..."
	$(GERRIT_ROOT)/gerrit-installer/create_installer.sh $(RELEASE_WAR_PATH) $(CONSOLE_API_STANDALONE_JAR_PATH)

	@echo "\n************ Installer Phase Finished **************"
.PHONY:installer

skip-tests:
	@echo "Skipping integration tests."
.PHONY:skip-tests

# Target used to check if the jenkins tmp directory exists, and if not to use
# /tmp on a users dev box.
testing_location:

	./build-tools/setup-environment.sh
	@echo "Testing location for temp assets is now: $(GERRIT_TEST_LOCATION)"

.PHONY:testing_location

run-acceptance-tests:
	@echo "\n************ Acceptance Tests Starting **************"
	@echo "About to run the Gerrit Acceptance Tests. These are the minimum required set of tests needed to run to verify Gerrits integrity."
	@echo "We specify GERRITMS_REPLICATION_DISABLED=true so that replication is disabled."
	@echo "Tests with the following labels in their BUILD files are disabled : [ elastic, docker, disabled ]"

	bazelisk test --cache_test_results=NO --test_env=GERRITMS_REPLICATION_DISABLED=true --test_tag_filters=-elastic,-docker,-disabled,-replication,-delete-project  //...

	@echo "\n************ Acceptance Tests Finished **************"
.PHONY:run-acceptance-tests

deploy: deploy-console deploy-gerrit
.PHONY:deploy

# Deploys all gerritms assets
deploy-all-gerrit:
	@echo "\n************ Deploying All GerritMS Assets **************"
	@echo "All GerritMS assets will be deployed."
	@echo "[ release.war, gerritms-installer.sh, lfs.jar, delete-project.jar, console-api.jar ]"
	@echo "Checking for available built assets"
	@echo "***********************************************************"
	./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) true
	@echo "***********************************************************"
	@echo "Beginning deployment of all assets"
	# NOTE that the repositoryId is currently 'artifacts'. There will be a check in future whereby we
	# may want to deploy to libs-release-local in which case the repositoryId would be 'releases'
	./build-tools/deploy-gerrit-assets.sh $(VERSION) $(ARTIFACT_REPO) artifacts

.PHONY:deploy-all-gerrit

#Still available to use but considered deprecated in favour of deploy-all-gerrit
deploy-gerrit:
	@echo "\n************ Deploy GerritMS Starting **************"
	@echo "The gerrit release.war and the gerrit-installer.sh will be deployed"

	@echo "\nChecking for the required assets."
	./build-tools/list_asset_locations.sh $(JENKINS_WORKSPACE) true

	@echo "Running mvn deploy:deploy-file to deploy the GerritMS artifacts to Artifactory..."
	@echo "Deploying as version: $(VERSION)"

	#Deploying the release.war to com.google.gerrit/gerritms
	mvn -X deploy:deploy-file \
		-DgroupId=$(GERRITMS_GROUPID) \
		-DartifactId=$(GERRITMS_ARTIFACTID) \
		-Dversion=$(VERSION) \
		-Dpackaging=war \
		-Dfile=$(RELEASE_WAR_PATH) \
		-DrepositoryId=artifacts \
		-Durl=$(ARTIFACTORY_DESTINATION)

	#Deploying the gerritms-installer.sh to com.google.gerrit/gerritms-installer
	mvn -X deploy:deploy-file \
		-DgroupId=$(GERRITMS_GROUPID) \
		-DartifactId=$(GERRITMS_INSTALLER_ARTIFACTID) \
		-Dversion=$(VERSION) \
		-Dfile=$(GERRITMS_INSTALLER_OUT) \
		-DrepositoryId=artifacts \
		-Durl=$(ARTIFACTORY_DESTINATION)

	@echo "\n************ Deploy  GerritMS Finished **************"

.PHONY:deploy-gerrit

#Still available to use but considered deprecated in favour of deploy-all-gerrit
deploy-console:
	@echo "\n************ Deploy Console-API Phase Starting **************"
	@echo "Running mvn deploy:deploy-file to deploy the console-api.jar to Artifactory..."
	@echo "Deploying as version: $(VERSION)"

	# use mvn deploy-file target, to deploy any file, and we will give it the pom properties to deploy as...
	mvn deploy:deploy-file \
	-DgroupId=$(GERRITMS_GROUPID) \
	-DartifactId=$(CONSOLE_ARTIFACTID) \
	-Dversion="$(VERSION)" \
	-Dpackaging=jar \
	-Dfile=$(CONSOLE_API_RELEASE_JAR_PATH) \
	-DrepositoryId=artifacts \
	-Durl=$(ARTIFACTORY_DESTINATION)
	@echo "\n************ Deploy Console-API Phase Finished **************"
.PHONY:deploy-console

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
	@echo "   make clean fast-assembly installer  -> will build the packages and installer asset"
	@echo "   make installer                    -> will build the installer asset using already built packages"
	@echo "   make run-acceptance-tests         -> will run the Gerrit acceptance tests, against the already built packages"
	@echo "   make list-assets                  -> Will list all assets from a built project, and return them in env var: ASSETS_FOUND"
	@echo "   make deploy                       -> will deploy the installer packages of GerritMS and ConsoleAPI to artifactory"
	@echo "   make deploy-gerrit                -> will deploy the installer package of GerritMS"
	@echo "   make deploy-all-gerrit            -> will deploy all the assets associated with GerritMS"
	@echo "   make deploy-console               -> will deploy the installer package of GerritMS Console API"
	@echo "   make help                         -> Display available targets"
.PHONY:help

