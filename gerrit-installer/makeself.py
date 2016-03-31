from os import path
import os
import io
from subprocess import call
import sys
import shutil

def absolutePath(parentDir, fileName):
  return path.join(parentDir, fileName)

def installFile(perms, fileName, destination):
  call(['install', '-m', perms, absolutePath(INSTALLER_DIR, fileName), destination])

def makeDirectory(directory):
  if not (path.exists(directory)):
    call(['mkdir', '-p', directory])

def createFiles():
  makeDirectory(GERRIT_DIR)
  makeDirectory(GERRIT_DIR + "/resources")
  installFile('0550', 'installer.sh', GERRIT_DIR)
  installFile('0550', 'sync_repo.sh', GERRIT_DIR)
  installFile('0550', 'reindex.sh', GERRIT_DIR)
  installFile('0640', 'create_tables.sql', GERRIT_DIR)
  installFile('0640', RELEASE_WAR, GERRIT_DIR)
  installFile('0640', 'resources/logo.txt', GERRIT_DIR + '/resources')

def makeself():
  os.system("makeself " + GERRIT_DIR + " gerritms-installer.sh \"GerritMS Installer\" ./installer.sh > /dev/null 2>&1")
  with open("gerritms-installer.sh", 'r') as fin:
    print fin.read()

  os.remove("gerritms-installer.sh")
  shutil.rmtree(GERRIT_DIR)

def generateInstaller():
  createFiles()
  makeself()


INSTALLER_DIR = path.dirname(sys.argv[0])
GERRIT_DIR = absolutePath(INSTALLER_DIR, 'gerrit-ms')
RELEASE_WAR = sys.argv[1]
VERSION = sys.argv[2]

generateInstaller()
