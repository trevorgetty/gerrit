#!/bin/bash --noprofile

echo "Creating installer"

rm -rf "target"
mkdir -p target/tmp/resources

RELEASE_WAR="${1}"
CONSOLE_API_JAR="${2}"

EXE_PERM=0777
NOEXE_PERM=0666

umask 000

if [ ! -f "${RELEASE_WAR}" ]; then
  echo "Error: release.war not found. Was \"buck build release\" run?"
  exit 1
fi

if [ ! -f "${CONSOLE_API_JAR}" ]; then
  echo "Error: ${CONSOLE_API_JAR} not found"
  exit 1
else
  install -m ${NOEXE_PERM} ${CONSOLE_API_JAR} target/tmp/console-api.jar
fi

install -m ${EXE_PERM} gerrit-installer/installer.sh target/tmp
install -m ${EXE_PERM} gerrit-installer/sync_repo.sh target/tmp
install -m ${EXE_PERM} gerrit-installer/reindex.sh target/tmp
install -m ${NOEXE_PERM} gerrit-installer/gerrit.service.template target/tmp
install -m ${NOEXE_PERM} gerrit-installer/resources/logo.txt target/tmp/resources
install -m ${NOEXE_PERM} ${RELEASE_WAR} target/tmp


makeself target/tmp gerritms-installer.sh "GerritMS Installer" ./installer.sh

if [ ! $? -eq 0 ]; then
  echo "Error: makeself failed to create installer package"
  exit 1
fi

rm -rf "target/tmp"
mv gerritms-installer.sh target
