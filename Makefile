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

# Works on OSX.
VERSION := $(shell $(GERRIT_ROOT)/build/get_version_number.sh $(GERRIT_ROOT))
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

all: display_version fast-assembly installer run-integration-tests

all-skip-tests: display_version fast-assembly installer skip-tests

display_version:
	@echo "About to use the following version information."
	@echo "Version is: $(VERSION)"
#
# Do an assembly without doing unit tests, of all our builds
#
fast-assembly: fast-assembly-gerrit fast-assembly-console

#
# Build just gerritMS
#
fast-assembly-gerrit:

	@echo "\n************ Compile Gerrit Starting **************"
	@echo "Building GerritMS"
	buck build release
	@echo "\n************ Compile Gerrit Finished **************"

#
# Build just the console-api
#
fast-assembly-console:
	@echo "\n************ Compile Console-API Starting **************"
	@echo "Building console-api"
	buck build //gerritconsoleapi:console-api
	@echo "\n************ Compile Console-API Finished **************"

clean: | $(JENKINS_DIRECTORY)
	@echo "\n************ Clean Phase Starting **************"
	buck clean
	rm -rf $(GERRIT_BUCK_OUT)
	rm -rf $(GERRIT_TEST_LOCATION)/jgit-update-service
	rm -f $(GERRIT_ROOT)/env.properties
	@echo "\n************ Clean Phase Finished **************"

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


installer: check_build_assets
	@echo "\n************ Installer Phase Starting **************"

	@echo "Building Gerrit Installer..."
	$(GERRIT_ROOT)/gerrit-installer/create_installer.sh $(RELEASE_WAR_PATH)/release.war $(CONSOLE_API_JAR_PATH)/console-api.jar

	@echo "\n************ Installer Phase Finished **************"


skip-tests:
	@echo "Skipping integration tests."

# Target used to check if the jenkins tmp directory exists, and if not to use
# /tmp on a users dev box.
$(JENKINS_DIRECTORY):

	@echo "Jenkins directory does not exist, fallback to /tmp on dev boxes"
	$(eval GERRIT_TEST_LOCATION = $(DEV_BOX_TMP_TEST_LOCATION))

	@echo Test directory is now: $(GERRIT_TEST_LOCATION)

	mkdir -p $(GERRIT_TEST_LOCATION)

run-integration-tests: check_build_assets | $(JENKINS_DIRECTORY)
	@echo "\n************ Integration Tests Starting **************"
	@echo "About to run integration tests -> resetting environment"

	@echo "Release war path in makefile is: $(RELEASE_WAR_PATH)"
	./build/run-integration-tests.sh $(RELEASE_WAR_PATH) $(GERRIT_TEST_LOCATION) $(CONSOLE_API_JAR_PATH) $(GITMS_VERSION)

	@echo "\n************ Integration Tests Finished **************"

deploy: deploy-console deploy-gerrit

deploy-gerrit:
	@echo "\n************ Deploy GerritMS Starting **************"
	@echo "TODO: For now skipping the deploy of GerritMS to artifactory."
	@echo "\n************ Deploy  GerritMS Finished **************"


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
	@echo "   make run-integration-tests        -> will run the integration tests, against the already built packages"

	@echo "   make deploy                       -> will deploy the installer packages of GerritMS and ConsoleAPI to artifactory"
	@echo "   make deploy-gerrit                -> will deploy the installer package of GerritMS"
	@echo "   make deploy-console               -> will deploy the installer package of GerritMS Console API"
	@echo "   make help                         -> Display available targets"

