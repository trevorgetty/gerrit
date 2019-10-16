#!/bin/bash

echo "Creating installer"

rm -rf "target"
mkdir -p target/tmp/resources

RELEASE_WAR="${1}"
CONSOLE_API_JAR="${2}"

if [ ! -f "${RELEASE_WAR}" ]; then
  echo "Error: release.war not found. Was \"buck build release\" run?"
  exit 1
fi

#skipping this and not exiting because we want this backward compatible.
if [ ! -f "${CONSOLE_API_JAR}" ]; then
  echo "Error: ${CONSOLE_API_JAR} not found"
  exit 1
else
  install -m 0640 ${CONSOLE_API_JAR} target/tmp
fi

install -m 0550 gerrit-installer/installer.sh target/tmp
install -m 0550 gerrit-installer/sync_repo.sh target/tmp
install -m 0550 gerrit-installer/reindex.sh target/tmp
install -m 0640 gerrit-installer/resources/logo.txt target/tmp/resources
install -m 0640 ${RELEASE_WAR} target/tmp


makeself target/tmp gerritms-installer.sh "GerritMS Installer" ./installer.sh

if [ ! $? -eq 0 ]; then
  echo "Error: makeself failed to create installer package"
  exit 1
fi

rm -rf "target/tmp"
mv gerritms-installer.sh target
