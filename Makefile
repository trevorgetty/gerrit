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
TEST_LOCATION=/var/lib/jenkins/tmp

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
	@echo "Building GerritMS"
	buck build release

#
# Build just the console-api
#
fast-assembly-console:
	@echo "Building console-api"
	buck build //gerritconsoleapi:console-api

clean:
	buck clean
	rm -rf $(GERRIT_BUCK_OUT)

installer:
	@echo "about to create installer packages"

	@echo "RELEASE_WAR_PATH=$(RELEASE_WAR_PATH)" >> "$(GERRIT_ROOT)/env.properties"
	@echo "INSTALLER_PATH=target" >> $(GERRIT_ROOT)/env.properties
	@echo "CONSOLE_API_JAR_PATH=$CONSOLE_API_JAR_PATH" >> $(GERRIT_ROOT)/env.properties
	@echo "Contents of env.properties is: $(GERRIT_ROOT)/env.properties)"

	[ -f $(RELEASE_WAR_PATH)/release.war ] && echo release.war exists || ( echo releaes.war not exists && exit 1;)
	[ -f $(CONSOLE_API_JAR_PATH)/console-api.jar ] && echo console-api.jar exists || ( echo console-api.jar not exists && exit 1;)

	@echo "Building Gerrit Installer..."
	$(GERRIT_ROOT)/gerrit-installer/create_installer.sh $(RELEASE_WAR_PATH)/release.war $(CONSOLE_API_JAR_PATH)/console-api.jar



skip-tests:
	@echo "Skipping integration tests."

run-integration-tests:
	@echo "About to run integration tests -> resetting environment"
	## Unset Jenkins ENV variables for Git commits
	unset GIT_AUTHOR_NAME
	unset GIT_AUTHOR_EMAIL
	unset GIT_AUTHOR_DATE
	unset GIT_COMMITTER_NAME
	unset GIT_COMMITTER_EMAIL
	unset GIT_COMMITTER_DATE
	unset EMAIL

	@echo "**************** Package Versions *****************"
		mysql --version
		python --version
	@echo "***************************************************"

	#Run integration tests with generated Gerrit MS war against latest Git MS
	git clone --depth 1 ssh://build.jenkins@gerrit-uk.wandisco.com:29418/jgit-update-service $GERRIT_TEST_LOCATION/jgit-update-service
	cp -a $RELEASE_WAR_PATH/release.war $GERRIT_TEST_LOCATION/jgit-update-service/gerrit.war
	cd $GERRIT_TEST_LOCATION/jgit-update-service

	#MySQL Setup
	mysql -uroot -e "CREATE USER 'jenkins'@'%' IDENTIFIED BY 'password';" || true
	mysql -uroot -e "CREATE USER 'jenkins'@'localhost' IDENTIFIED BY 'password';" || true

	make clean fast-assembly

	#echo "Starting integration tests"
	DELAY=60 ./test-rb/run/gerrit/default.sh

deploy: deploy-console deploy-gerrit

deploy-gerrit:
			@echo "Running mvn deploy:deploy-file to deploy the gerritMS installer to Artifactory..."

			@echo "TODO: For now skipping the deploy of GerritMS to artifactory."


deploy-console:
				@echo "Running mvn deploy:deploy-file to deploy the console-api.jar to Artifactory..."
				@echo "Deploying as version: $(VERSION)"

				#gerrit-server
				mvn deploy:deploy-file \
				-DgroupId=$(CONSOLE_GROUPID) \
				-DartifactId=$(CONSOLE_ARTIFACTID) \
				-Dversion="$(VERSION)" \
				-Dpackaging=jar \
				-Dfile=$(CONSOLE_API_JAR_PATH)/console-api.jar \
				-DrepositoryId=releases \
				-Durl=http://artifacts.wandisco.com:8081/artifactory/libs-release-local

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

	@echo "   make deploy                       -> will deploy the installer packages of GerritMS and ConsoleAPI to artifactory"
	@echo "   make deploy-gerrit                -> will deploy the installer package of GerritMS"
	@echo "   make deploy-console               -> will deploy the installer package of GerritMS Console API"
	@echo "   make help                         -> Display available targets"

