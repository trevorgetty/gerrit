#!/bin/bash

function info() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    echo -e "$1"
  fi
}

function header() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    clear
    cat "resources/logo.txt"
    info "\n\n"
  fi
}

function bold {
  local message="$1"
  info "\033[1m$message\033[0m"
}

function next_screen() {
  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    read -s -p "$1"
  fi
}

function urlencode() {
  local raw=$1
  local encoded=""
  local pos o

  for ((pos=0; pos < ${#raw}; pos++)); do
    c=${raw:$pos:1}
    case $c in
      [0-9a-zA-Z-_.~]) o=$c ;;
      *) printf -v o '%%%02x' "'$c" ;;
    esac
    encoded+="$o"
  done

  echo $encoded
}

## Removes double //'s from path
function sanitize_path() {
  local path=$(echo "$1" | tr -s /)
  echo "$path"
}

#Attempts to find the gerrit base path using the specified GERRIT_ROOT
function get_gerrit_base_path() {
    export GIT_CONFIG="${1}/etc/gerrit.config"
    local gerrit_base_path=$(git config gerrit.basePath)
    unset GIT_CONFIG
    echo "$gerrit_base_path"
}

function prereqs() {
  header

  bold " GerritMS Version: $WD_GERRIT_VERSION Installation"
  info ""
  bold " Install Documentation: "
  info ""
  info " $GERRITMS_INSTALL_DOC"
  info ""
  info " Welcome to the GerritMS installation. Before the install can continue,"
  info " you must:"
  info ""
  info " * Have Gerrit $NEW_GERRIT_VERSION installed before beginning"
  info " * Have backed up your existing Gerrit database"
  info " * Have a version of GitMS (1.5.2 or higher) installed and running"
  info " * Have a replication group created in GitMS containing all Gerrit nodes"
  info " * Have a valid GitMS admin username/password"
  info " * Have a valid Gerrit admin username/password"
  info " * Stop the Gerrit service on this node"
  info ""

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    REQUIREMENTS_MET=$(get_boolean "Do you want to continue with the installation?" "true")
    info
    if [ "$REQUIREMENTS_MET" == "false" ]; then
      info "Installation aborted by user"
      exit 0
    fi
    FIRST_NODE=$(get_boolean "Is this the first node GerritMS will be installed to?" "true")
  fi
}

function check_user() {
  header
  local user=$(id -u -n)

  info " Currently installing as user: \033[1m$user\033[0m"
  info ""

  if [ "$EUID" -eq 0 ]; then
    info " \033[1mWARNING:\033[0m It is strongly advised that the GitMS and Gerrit services"
    info " are not run as root."
    info ""
  fi

  info " The current user should be the same as the owner of the GitMS service."
  info " If this is not currently the case, please exit and re-run the installer"
  info " as the appropriate user."
  info ""
  next_screen " Press [Enter] to Continue"
}

function check_executables() {
  for bin in zip unzip find xmllint git md5sum mysql; do
    if ! type -p "$bin" >/dev/null 2>&1; then
      header
      info  " Could not find \033[1m$bin\033[0m command, please install it"
      info ""
    fi
  done
  return 1
}

function create_scratch_dir() {
  SCRATCH=$(mktemp -d)
}

## Check in ~/.gitconfig for the gitmsconfig property
## gitms_root can be derived from this
function find_gitms() {
  GIT_CONFIG="$HOME/.gitconfig"
  export GIT_CONFIG
  local gitms_config=$(git config core.gitmsconfig)

  if [[ -z "$gitms_config" || ! -e "$gitms_config" ]]; then
    ## check if the default gitms install folder is present
    if [ -d "/opt/wandisco/git-multisite" ]; then
      GITMS_ROOT="/opt/wandisco/git-multisite"
    else
      return
    fi
  else
    GITMS_ROOT=${gitms_config/"/replicator/properties/application.properties"/""}
  fi

  GITMS_ROOT_PROMPT=" [$GITMS_ROOT]"
}

function fetch_property() {
  cat "$APPLICATION_PROPERTIES" | grep -o "^$1.*" | cut -f2- -d'='
}

## Set a property in TMP_APPLICATION_PROPERTIES - also make sure any duplicate
## properties with the same key are not present by removing them
function set_property() {
  local tmp_file="$TMP_APPLICATION_PROPERTIES.tmp"
  ## First delete the property - this should not be necessary, but doesn't hurt
  ## The property must have the .'s escaped when we delete them, or they'll match
  ## against any character
  local escaped_property=$(echo "$1" | sed 's/\./\\./g')
  sed -e "/^$escaped_property=/ d" "$TMP_APPLICATION_PROPERTIES" > "$tmp_file" && mv "$tmp_file" "$TMP_APPLICATION_PROPERTIES"

  echo "$1=$2" >> "$TMP_APPLICATION_PROPERTIES"
}

## With the GitMS root location, we can look up a lot of information
## and avoid asking the user questions
function fetch_config_from_application_properties() {
  SSL_ENABLED=$(fetch_property "ssl.enabled")
  GERRIT_ENABLED=$(fetch_property "gerrit.enabled")
  GERRIT_ROOT=$(fetch_property "gerrit.root")
  GERRIT_USERNAME=$(fetch_property "gerrit.username")
  GERRIT_PASSWORD=$(fetch_property "gerrit.password")
  GERRIT_RPGROUP_ID=$(fetch_property "gerrit.rpgroupid")
  GERRIT_REPO_HOME=$(fetch_property "gerrit.repo.home")
  GERRIT_EVENTS_PATH=$(fetch_property "gerrit.events.basepath")
  GERRIT_DB_SLAVEMODE_SLEEPTIME=$(fetch_property "gerrit.db.slavement.sleepTime")
  GITMS_REST_PORT=$(fetch_property "jetty.http.port")
  GITMS_SSL_REST_PORT=$(fetch_property "jetty.https.port")
  GERRIT_REPLICATED_EVENTS_SEND=$(fetch_property "gerrit.replicated.events.enabled.send")
  GERRIT_REPLICATED_EVENTS_RECEIVE=$(fetch_property "gerrit.replicated.events.enabled.receive")
}

## Get a password from the user, blanking the input, places it into the
## USER_PASSWORD env
function get_password() {
  USER_PASSWORD=""
  while true
  do
    read -s -e -p " $1: " USER_PASSWORD
    if [ ! -z "$USER_PASSWORD" ]; then
      break
    fi
  done
}

## Reads input until the user specifies a directory which both exists
## and we have read access to.
## $1: String to display in the prompt
## $2: Whether to prompt if we should create the directory
function get_directory() {

  local create_directory="false"

  if [ "$2" == "true" ]; then
    create_directory="true"
  fi

  while true
  do
    INPUT_DIR=$(get_string "$1")

    ## If the directory does not exist and create_directory is true, offer to create it
    if [[ ! -d "$INPUT_DIR" && ! -e "$INPUT_DIR" && "$create_directory" == "true" ]]; then
      local create_dir=$(get_boolean "This directory does not exist, do you want to create it?" "true")

      if [ "$create_dir" == "true" ]; then
        mkdir -p "$INPUT_DIR"
        if [ "$?" == "0" ]; then
          break
        else
          echo " ERROR: Directory could not be created"
        fi
      fi
    fi

    if [ -d "$INPUT_DIR" ]; then
      if [ -w "$INPUT_DIR" ]; then
        break
      else
        echo " ERROR: $INPUT_DIR is not writable"
      fi
    else
      echo " ERROR: $INPUT_DIR does not exist"
    fi
  done
}

## Reats input until the user specifies a string which is not empty
## $1: The string to display
## $2: Set to "true" to allow an empty imput
function get_string() {

  local allow_empty_string="false"

  if [ ! -z "$2" ]; then
    allow_empty_string=$(echo "$2" | tr '[:upper:]' '[:lower:]')

    if [ ! "$allow_empty_string" == "true" ]; then
      allow_empty_string="false"
    fi
  else
    allow_empty_string="false"
  fi

  while true
  do
    read -e -p " $1: " INPUT
    if [ ! -z "$INPUT" ]; then
      break
    fi

    if [ "$allow_empty_string" == "true" ]; then
      break
    fi
  done

  echo "$INPUT"
}

## Convert Y/y/N/n to boolean equivalents
function to_boolean() {
  local bool=$(echo "$1" | tr '[:lower:]' '[:upper:]')

  if [ "$bool" == "Y" ]; then
    echo "true"
  elif [ "$bool" == "N" ]; then
    echo "false"
  fi
}

## fetch a true or false from the user when displaying a Y/N prompt
## $1: The string to display
## $2: "true" or "false" default selection
function get_boolean() {

  local option_yes="y"
  local option_no="n"
  local default=$(echo "$2" | tr '[:upper:]' '[:lower:]')

  if [ "$default" == "true" ]; then
    option_yes="Y"
  else
    ## force default to false if it's not true
    default="false"
    option_no="N"
  fi

  local option_string="[${option_yes}/${option_no}]"

  while true
  do
    INPUT=$(get_string "$1 $option_string" "true")

    if [ -z "$INPUT" ]; then
      echo "$default"
      break
    fi

    INPUT=$(to_boolean "$INPUT")

    if [[ "$INPUT" == "true" || "$INPUT" == "false" ]]; then
      echo "$INPUT"
      break
    fi
  done
}

## Determine the Gerrit Replication Group ID by allowing the user to type in the
## name of the Replication Group in GitMS.
function get_gerrit_replication_group_id() {
  local group_name=$(get_string "Gerrit Replication Group Name")
  local username=$(get_string "GitMS Admin Username")
  get_password "GitMS Admin Password"
  local password="$USER_PASSWORD"
  local tmpFile=$(mktemp --tmpdir="$SCRATCH")

  ## The port GitMS uses will depend on whether SSL is enabled or not.
  if [ "$SSL_ENABLED" == "true" ]; then
    local protocol="https://"
    local rest_port="$GITMS_SSL_REST_PORT"
  else
    local protocol="http://"
    local rest_port="$GITMS_REST_PORT"
  fi

  group_name=$(urlencode "$group_name")
  local url="$protocol"
  url+="127.0.0.1:$rest_port/api/replication-groups/search?groupName=$group_name"

  curl -u "$username:$password" -s -k -f "$url" > "$tmpFile"
  local group_id=$(grep -oPm1 '(?<=<replicationGroupIdentity>)[^<]+' "$tmpFile")
  echo "$group_id"
}

## Check a Gerrit root for a valid gerrit install
function check_gerrit_root() {
  local gerrit_root="$1"
  ## Make sure that GERRIT_ROOT/etc/gerrit.config exists
  local gerrit_config="$(sanitize_path "${gerrit_root}/etc/gerrit.config")"

  if [ ! -e "$gerrit_config" ]; then
    echo "ERROR: $gerrit_config does not exist, invalid Gerrit Root directory"
    return 1
  fi

  ## Get the location of the gerrit.war file

  ## default location
  local gerrit_war_path="${gerrit_root}/bin/gerrit.war"
  GIT_CONFIG="$gerrit_config"
  export GIT_CONFIG
  local container_war_prop=$(git config container.war)

  if [ ! -z "$container_war_prop" ]; then
    ## container.war is set, gerrit.war may be elsewhere

    if [[ "$container_war_prop" = /* ]]; then
      ## absolute path
      gerrit_war_path="$container_war_prop"
    else
      ## relative path
      gerrit_war_path="${GERRIT_ROOT}/${container_war_prop}"
    fi
  fi

  if [ ! -e "$gerrit_war_path" ]; then
    echo " ERROR: $gerrit_war_path does not exist"
    return 1
  fi

  ## Check the version of the detected gerrit.war
  OLD_GERRIT_VERSION=$(get_gerrit_version "$gerrit_war_path")
  OLD_GERRIT_VERSION=$(echo "$OLD_GERRIT_VERSION" | cut -f1 -d"-")

  if [ ! "$OLD_GERRIT_VERSION" == "$NEW_GERRIT_VERSION" ]; then
    echo -e " \033[1mERROR:\033[0m Gerrit version detected at this location is at version: $OLD_GERRIT_VERSION"
    echo " The current Gerrit version should be: $NEW_GERRIT_VERSION"
    return 1
  fi

  return 0
}


function get_config_from_user() {
  header
  bold " Configuration Information"
  info ""
  find_gitms

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    while true
    do
      read -e -p " Git Multisite root directory$GITMS_ROOT_PROMPT: " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$GITMS_ROOT
      fi

      if [[ -d "$INPUT" && -r "$INPUT" ]]; then
        ## The directory exists, but we must ensure it has an application.properties file
        APPLICATION_PROPERTIES="$INPUT"
        APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

        if [ ! -e "$APPLICATION_PROPERTIES" ]; then
          info ""
          info " \033[1mERROR:\033[0m $APPLICATION_PROPERTIES cannot be found"
          info ""
          continue
        fi

        break
      else
        info ""
        info " \033[1mERROR:\033[0m directory does not exist or is not readable"
        info ""
      fi
    done
    GITMS_ROOT="$INPUT"

    info ""
    bold " Reading GitMS Configuration..."
    info ""
    fetch_config_from_application_properties
  fi

  ## Copy APPLICATION_PROPERTIES to SCRATCH, so an install aborted part way
  ## will not have modified existing install.
  cp "$APPLICATION_PROPERTIES" "$SCRATCH"
  TMP_APPLICATION_PROPERTIES="$SCRATCH/application.properties"

  ## This is either an upgrade for an install which has already used GerritMS, or
  ## it is a clean install. Look at the application.properties to determine if
  ## any necessary values are not present. Only offer to set values which are
  ## not already set.

  if [ -z "$GERRIT_ENABLED" ]; then
    set_property "gerrit.enabled" "true"
  fi

  if [ -z "$GERRIT_ROOT" ]; then

    while true
    do
      get_directory "Gerrit Root Directory" "false"

      ## Check the Gerrit install at this location is good
      check_gerrit_root "$INPUT_DIR"

      if [ ! "$?" == "0" ]; then
        continue
      fi

      break
    done
    GERRIT_ROOT="$INPUT_DIR"
  else
    info " Gerrit Root Directory: $GERRIT_ROOT"

    ## GERRIT_ROOT is either set in non-interactive mode, or from application.properties
    ## It should still be verified, but in this case, a failure exits the install
    ## rather than reprompting for input
    check_gerrit_root "$GERRIT_ROOT"

    if [ "$?" == "1" ]; then
      echo " Exiting install, $GERRIT_ROOT does not point to a valid Gerrit install."
      exit 1;
    fi
  fi

  set_property "gerrit.root" "$GERRIT_ROOT"

  if [ -z "$GERRIT_USERNAME" ]; then
    GERRIT_USERNAME=$(get_string "Gerrit Admin Username")
  else
    info " Gerrit Admin Username: $GERRIT_USERNAME"
  fi

  set_property "gerrit.username" "$GERRIT_USERNAME"

  if [ -z "$GERRIT_PASSWORD" ]; then
    while true
    do
      get_password "Gerrit Admin Password"
      info ""
      local pw_one="$USER_PASSWORD"
      get_password "Confirm Gerrit Admin Password"
      info ""
      local pw_two="$USER_PASSWORD"

      if [ "$pw_one" == "$pw_two" ]; then
        GERRIT_PASSWORD="$pw_one"
        break
      else
        info " \033[1mWARNING:\033[0m: Passwords did not match"
      fi
    done

  else
    info " Gerrit Admin Password: ********"
  fi

  set_property "gerrit.password" "$GERRIT_PASSWORD"

  if [ -z "$GERRIT_REPO_HOME" ]; then
    get_directory "Gerrit Repository Directory" "false"
    GERRIT_REPO_HOME="$INPUT_DIR"
  else
    info " Gerrit Repository Directory: $GERRIT_REPO_HOME"
  fi

  set_property "gerrit.repo.home" "$GERRIT_REPO_HOME"

  if [ -z "$GERRIT_EVENTS_PATH" ]; then
    get_directory "Gerrit Events Directory" "true"
    GERRIT_EVENTS_PATH="$INPUT_DIR"
  else
    info " Gerrit Events Path: $GERRIT_EVENTS_PATH"
  fi

  set_property "gerrit.events.basepath" "$GERRIT_EVENTS_PATH"

  if [ -z "$GERRIT_REPLICATED_EVENTS_SEND" ]; then
    GERRIT_REPLICATED_EVENTS_SEND=$(get_boolean "Will this node send Replicated Events to other Gerrit nodes?" "true")
  else
    info " Gerrit Receive Replicated Events: $GERRIT_REPLICATED_EVENTS_SEND"
  fi

  set_property "gerrit.replicated.events.enabled.send" "$GERRIT_REPLICATED_EVENTS_SEND"


  if [ -z "$GERRIT_REPLICATED_EVENTS_RECEIVE" ]; then
    GERRIT_REPLICATED_EVENTS_RECEIVE=$(get_boolean "Will this node receive Replicated Events from other Gerrit nodes?" "true")
  else
    info " Gerrit Send Replicated Events: $GERRIT_REPLICATED_EVENTS_RECEIVE"
  fi

  set_property "gerrit.replicated.events.enabled.receive" "$GERRIT_REPLICATED_EVENTS_RECEIVE"

  if [ -z "$GERRIT_RPGROUP_ID" ]; then
    while true
    do
      GERRIT_RPGROUP_ID=$(get_gerrit_replication_group_id)

      if [ ! -z "$GERRIT_RPGROUP_ID" ]; then
        info ""
        info " Replication Group found with ID: $GERRIT_RPGROUP_ID"
        break
      else
        info ""
        info " \033[1mERROR:\033[0m Could not retrieve Replication Group ID with configuration provided"
        info ""
      fi
    done
  else
    info " Gerrit Replication Group ID: $GERRIT_RPGROUP_ID"
  fi

  set_property "gerrit.rpgroupid" "$GERRIT_RPGROUP_ID"


  if [ -z "$GERRIT_DB_SLAVEMODE_SLEEPTIME" ]; then
    set_property "gerrit.db.slavemode.sleepTime" "100"
  fi

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    info ""
    bold " Helper Scripts"
    info ""
    info " We provide some optional scripts to aid in installation/administration of "
    info " GerritMS. Where should these scripts be installed?"
    info ""

    local default=$(sanitize_path "$GERRIT_ROOT/bin")
    while true
    do
      read -e -p " Helper Script Install Directory [$default]: " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$default
      fi

      if [[ -d "$INPUT" && -w "$INPUT" ]]; then
        break
      else
        info ""
        info " \033[1mERROR:\033[0m directory does not exist or is not writable"
        info ""
      fi
    done
    SCRIPT_INSTALL_DIR="$INPUT"
  fi
}

## Creates a temporary file with the credentials for the database
## $1: username
## $2: password
## $3: hostname
function create_db_extras_file() {
  local extras_file=$(mktemp --tmpdir="$SCRATCH")

  ## It's important to rewrite 'localhost' as '127.0.0.1' to force mysql
  ## to connect via TCP here
  local mysql_host="$3"
  if [ "$mysql_host" == "localhost" ]; then
    mysql_host="127.0.0.1"
  fi

  echo -e "[client]\nuser=${1}\npassword=${2}\nhost=${mysql_host}" >> "$extras_file"
  echo "$extras_file"
}

function create_backup() {
  header
  bold " Backup Information"
  info ""
  info " Taking a backup of the of GitMS + Gerrit configuration. Where should this"
  info " be saved?"
  info ""

  if [ ! "$NON_INTERACTIVE" == "1" ]; then
    get_directory "Backup Location" "true"
    BACKUP_ROOT="$INPUT_DIR"
  else
    BACKUP_ROOT="$BACKUP_DIR"
  fi

  mkdir -p "$SCRATCH/backup/gerrit"

  if [ "$FIRST_NODE" == "true" ]; then
    ## Fetch some properties from gerrit.config to potentially backup the database
    GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config"
    export GIT_CONFIG
    local db_name=$(git config database.database)
    unset GIT_CONFIG

    info ""
    info " \033[1mNOTE:\033[0m This instance of Gerrit has been configured to use the database $db_name."
    info " It is recommended that you create a backup of this database now if one"
    info " does not exist already."
    info ""
  fi

  ## Should backup the GERRIT_ROOT directories, excluding the gerrit.basePath
  ## as well as the GitMS application.properties file before any modifications we
  ## might make to it.
  info " Creating backup..."
  local timestamped_backup=$(date +%Y%m%d%H%M%S)
  timestamped_backup+=".tar.gz"

  local gerrit_base_path=$(get_gerrit_base_path "$GERRIT_ROOT")

  if [[ $gerrit_base_path = /* ]]; then
    gerrit_base_path=${gerrit_base_path#$GERRIT_ROOT/}
  fi

  rsync -aq --exclude="$gerrit_base_path" "$GERRIT_ROOT/" "${SCRATCH}/backup/gerrit" > /dev/null 2>&1
  cp "$APPLICATION_PROPERTIES" "${SCRATCH}/backup"

  pushd ./ > /dev/null 2>&1
    cd "$SCRATCH"
    tar -zcpf "${BACKUP_ROOT}/gerrit-backup-${timestamped_backup}" "backup" > /dev/null 2>&1
  popd > /dev/null 2>&1

  info " Backup saved to: \033[1m$(sanitize_path "${BACKUP_ROOT}/gerrit-backup-${timestamped_backup}")\033[0m"

  info ""
  next_screen " Press [Enter] to Continue"
}

function write_gitms_config() {
  mv "$TMP_APPLICATION_PROPERTIES" "$APPLICATION_PROPERTIES"
  git config --global core.gitmsconfig "$APPLICATION_PROPERTIES"
  info " GitMS Configuration Updated"
}

function add_master_slave_db_tables() {
  GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config"
  export GIT_CONFIG
  local dbType=$(git config database.type)
  local dbHostname=$(git config database.hostname)
  local dbName=$(git config database.database)
  local dbUsername=$(git config database.username)
  local dbType=$(echo "$dbType" | tr '[:upper:]' '[:lower:]')
  local dbPort=$(git config database.port)

  if [ -z "$dbPort" ]; then
    dbPort="3306"
  fi

  if [ "$dbType" != "mysql" ]; then
    info " \033[1mWARNING:\033[0m Gerrit database is not a MySQL database, master-slave tables will not be added"
    return 0
  fi

  GIT_CONFIG="$GERRIT_ROOT/etc/secure.config"
  export GIT_CONFIG
  local dbPassword=$(git config database.password)
  unset GIT_CONFIG

  DB_EXTRAS_FILE=$(create_db_extras_file "$dbUsername" "$dbPassword" "$dbHostname")

  tableCount=$(mysql --defaults-extra-file="$DB_EXTRAS_FILE" --port="$dbPort" -e "select count(*) from information_schema.tables where table_schema='$dbName' and table_name='slave_wait_id'" -B --disable-column-names 2>/dev/null)

  if [ $? -ne 0 ]; then
    info ""
    echo -e " \033[1mERROR:\033[0m Could not connect to database. Ensure the database credentials in the"
    echo " Gerrit configuration are correct and the service is running, then retry."
    info ""
    exit 1
  fi

  if [ $tableCount -eq 0 ]; then
    mysql --defaults-extra-file="$DB_EXTRAS_FILE" --port="$dbPort" -D"$dbName" < create_tables.sql
    if [ $? -eq 0 ]; then
      info " Gerrit database has been updated with tables required for optional master-slave setup"
    elif [ $? -eq 1 ]; then
      echo -e " \033[1mERROR:\033[0m Tables for optional master-slave setup could not be added to the Gerrit database"
      exit 1
    fi
  fi
}

function write_gerrit_config() {

  ## Make changes to the DB first
  if [ "$FIRST_NODE" == "true" ]; then
    add_master_slave_db_tables
  fi

  local gerrit_config="$GERRIT_ROOT/etc/gerrit.config"
  local tmp_gerrit_config="$SCRATCH/gerrit.config"
  cp "$gerrit_config" "$tmp_gerrit_config"

  GIT_CONFIG="$tmp_gerrit_config"
  export GIT_CONFIG

  git config --remove-section cache >/dev/null 2>&1 || true
  git config cache.accounts.memorylimit 0
  git config cache.accounts.disklimit 0
  git config cache.accounts_byemail.memorylimit 0
  git config cache.accounts_byemail.disklimit 0
  git config cache.adv_bases.memorylimit 0
  git config cache.adv_bases.disklimit 0
  git config cache.changes.memorylimit 0
  git config cache.changes.disklimit 0
  git config cache.diff.memorylimit 0
  git config cache.diff.disklimit 0
  git config cache.diff_intraline.memorylimit 0
  git config cache.diff_intraline.disklimit 0
  git config cache.git_tags.memorylimit 0
  git config cache.git_tags.disklimit 0
  git config cache.groups.memorylimit 0
  git config cache.groups.disklimit 0
  git config cache.groups_byinclude.memorylimit 0
  git config cache.groups_byinclude.disklimit 0
  git config cache.ldap_groups.memorylimit 0
  git config cache.ldap_groups.disklimit 0
  git config cache.permission_sort.memorylimit 0
  git config cache.permission_sort.disklimit 0
  git config cache.plugin_resources.memorylimit 0
  git config cache.plugin_resources.disklimit 0
  git config cache.projects.memorylimit 0
  git config cache.projects.disklimit 0
  git config cache.projects.checkfrequency 0
  git config cache.project_list.memorylimit 0
  git config cache.project_list.disklimit 0
  git config cache.sshkeys.memorylimit 0
  git config cache.sshkeys.disklimit 0
  git config receive.timeout 900000

  unset GIT_CONFIG

  mv "$tmp_gerrit_config" "$gerrit_config"

  info ""
  info " Gerrit Configuration Updated"
}

function replace_gerrit_war() {
  RELEASE_WAR="release.war"
  OLD_WAR="$GERRIT_ROOT/bin/gerrit.war"

  cp "$RELEASE_WAR" "$OLD_WAR"
}

## If the user doesn't re-init Gerrit following an upgrade, we can have an old
## version of the GitMS-Gerrit-Event-Plugin still in use. Forcibly remove it
## and put in the new version.
function replace_gitms_gerrit_plugin() {
  local plugin_location="$GERRIT_ROOT/plugins/gitms-gerrit-event-plugin.jar"

  if [ -e "$plugin_location" ]; then
    rm -f "$plugin_location"
  fi

  local tmpdir=$(mktemp -d --tmpdir="$SCRATCH")
  unzip -o "$GERRIT_ROOT/bin/gerrit.war" -d "$tmpdir" WEB-INF/plugins/gitms-gerrit-event-plugin.jar >/dev/null 2>&1
  cp "$tmpdir/WEB-INF/plugins/gitms-gerrit-event-plugin.jar" "$plugin_location"
}

function install_gerrit_scripts() {
  cp -f "reindex.sh" "$SCRIPT_INSTALL_DIR"
  cp -f "sync_repo.sh" "$SCRIPT_INSTALL_DIR"
}

function write_new_config() {
  header
  bold " Finalizing Install"

  write_gerrit_config
  write_gitms_config
  replace_gerrit_war
  replace_gitms_gerrit_plugin
  install_gerrit_scripts

  info ""
  info " GitMS and Gerrit have now been configured."
  info ""
  bold " Next Steps:"
  info ""
  info " * Restart GitMS on this node now to finalize the configuration"

  if [ ! "$FIRST_NODE" == "true" ]; then
    info " * If you have rsync'd this Gerrit installation from a previous node"
    info "   please ensure you have updated the $(sanitize_path "${GERRIT_ROOT}/etc/gerrit.config")"
    info "   file for this node. In particular, the canonicalWebUrl and database settings should"
    info "   be verified to be correct for this node."
  else
    local gerrit_base_path=$(get_gerrit_base_path "$GERRIT_ROOT")

    info " * rsync $GERRIT_ROOT to all of your GerritMS nodes"
    if [[ "${gerrit_base_path#$GERRIT_ROOT/}" = /* ]]; then
      info " * rsync $gerrit_base_path to all of your GerritMS nodes"
    fi
    info " * Run this installer on all of your other Gerrit nodes"
    info " * On each of your Gerrit nodes, update gerrit.config:"
    info "\t- change the hostname of canonicalURL to the hostname for that node"
    info "\t- ensure that database details are correct"
    info " * Run $(sanitize_path "$SCRIPT_INSTALL_DIR/sync_repo.sh") on one node to add any existing"
    info "   Gerrit repositories to GitMS. Note that even if this is a new install of"
    info "   Gerrit with no user added repositories, running sync_repo.sh is still"
    info "   required to ensure that All-Projects is properly replicated."
    info " * When all nodes have been installed, you are ready to start the Gerrit services"
    info "   across all nodes."
  fi

  info ""

  if [ "$NON_INTERACTIVE" == "1" ]; then
    echo "Non-interactive install completed"
  fi
}

## Determine the version of a Gerrit war file
function get_gerrit_version() {
  local tmpdir=$(mktemp -d --tmpdir="$SCRATCH")
  unzip -o "$1" -d "$tmpdir" WEB-INF/lib/gerrit-war-version.jar >/dev/null 2>&1
  unzip -p "$tmpdir/WEB-INF/lib/gerrit-war-version.jar" > "$tmpdir/GERRIT-VERSION"
  echo $(cat "$tmpdir/GERRIT-VERSION")
}

## Cleanup installation temp files
function cleanup() {
  if [[ ! -z "$SCRATCH" && -d "$SCRATCH" ]]; then
    rm -rf "$SCRATCH"
  fi
}

## Check for non-interactive mode. If all the required environment variables are set,
## we can skip asking questions

## How many env variables are needed to initiate a non-interactive install
## depends on what properties are already set in application.properties.
## If a property is set there, it is not required as an env variable. Otherwise
## it is.

## Additionally, sanity checking is done here to make sure that application.properties
## and GERRIT_ROOT exist
function check_for_non_interactive_mode() {
  ## These properties will not be set in applicaton.properties
  if [[ ! -z "$GITMS_ROOT" && ! -z "$BACKUP_DIR" && ! -z "$SCRIPT_INSTALL_DIR" && ! -z "$FIRST_NODE" ]]; then

    APPLICATION_PROPERTIES="$GITMS_ROOT"
    APPLICATION_PROPERTIES+="/replicator/properties/application.properties"

    if [ ! -e "$APPLICATION_PROPERTIES" ]; then
      info "ERROR: Non-interactive installation aborted, the file $APPLICATION_PROPERTIES does not exist"
      exit 1
    fi

    ## Need to fetch values from application.properties first, as they should take
    ## precedence over env variables
    local tmp_gerrit_root=$(fetch_property "gerrit.root")
    local tmp_gerrit_username=$(fetch_property "gerrit.username")
    local tmp_gerrit_password=$(fetch_property "gerrit.password")
    local tmp_gerrit_rpgroup_id=$(fetch_property "gerrit.rpgroupid")
    local tmp_gerrit_repo_home=$(fetch_property "gerrit.repo.home")
    local tmp_gerrit_events_path=$(fetch_property "gerrit.events.basepath")
    local tmp_gerrit_replicated_events_send=$(fetch_property "gerrit.replicated.events.enabled.send")
    local tmp_gerrit_replicated_events_receive=$(fetch_property "gerrit.replicated.events.enabled.receive")

    ## Override env variables where the property already exists
    if [ ! -z "$tmp_gerrit_root" ]; then
      GERRIT_ROOT="$tmp_gerrit_root"
    fi

    if [ ! -z "$tmp_gerrit_username" ]; then
      GERRIT_USERNAME="$tmp_gerrit_username"
    fi

    if [ ! -z "$tmp_gerrit_password" ]; then
      GERRIT_PASSWORD="$tmp_gerrit_password"
    fi

    if [ ! -z "$tmp_gerrit_rpgroup_id" ]; then
      GERRIT_RPGROUP_ID="$tmp_gerrit_rpgroup_id"
    fi

    if [ ! -z "$tmp_gerrit_repo_home" ]; then
      GERRIT_REPO_HOME="$tmp_gerrit_repo_home"
    fi

    if [ ! -z "$tmp_gerrit_events_path" ]; then
      GERRIT_EVENTS_PATH="$tmp_gerrit_events_path"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_send" ]; then
      GERRIT_REPLICATED_EVENTS_SEND="$tmp_gerrit_replicated_events_send"
    fi

    if [ ! -z "$tmp_gerrit_replicated_events_receive" ]; then
      GERRIT_REPLICATED_EVENTS_RECEIVE="$tmp_gerrit_replicated_events_receive"
    fi


    ## Check that all variables are now set to something
    if [[ ! -z "$GERRIT_ROOT" && ! -z "$GERRIT_USERNAME" && ! -z "$GERRIT_PASSWORD"
      && ! -z "$GERRIT_RPGROUP_ID" && ! -z "$GERRIT_REPO_HOME" && ! -z "$GERRIT_EVENTS_PATH"
      && ! -z "$GERRIT_REPLICATED_EVENTS_SEND" && ! -z "$GERRIT_REPLICATED_EVENTS_RECEIVE" ]]; then

      ## GERRIT_ROOT must exist as well
      if [ ! -d "$GERRIT_ROOT" ]; then
        info "ERROR: Non-interactive installation aborted, the GERRIT_ROOT at $GERRIT_ROOT does not exist"
        exit 1
      fi
      NON_INTERACTIVE=1
    fi
  fi
}

NON_INTERACTIVE=0
WD_GERRIT_VERSION=$(get_gerrit_version "release.war")
NEW_GERRIT_VERSION=$(echo $WD_GERRIT_VERSION | cut -f1 -d '-')
GERRIT_RELEASE_NOTES="http://gerrit-documentation.storage.googleapis.com/ReleaseNotes/ReleaseNotes-2.9.4.html"
GERRITMS_INSTALL_DOC="http://docs.wandisco.com/git/gerrit/1.2/gerrit_install.html"

check_for_non_interactive_mode

if [ "$NON_INTERACTIVE" == "1" ]; then
  echo "Starting non-interactive install of GerritMS..."
  echo ""
  echo "Using settings: "
  echo "GITMS_ROOT: $GITMS_ROOT"
  echo "GERRIT_ROOT: $GERRIT_ROOT"
  echo "GERRIT_USERNAME: $GERRIT_USERNAME"
  echo "GERRIT_PASSWORD: $GERRIT_PASSWORD"
  echo "GERRIT_RPGROUP_ID: $GERRIT_RPGROUP_ID"
  echo "GERRIT_REPO_HOME: $GERRIT_REPO_HOME"
  echo "GERRIT_EVENTS_PATH: $GERRIT_EVENTS_PATH"
  echo "GERRIT_REPLICATED_EVENTS_SEND: $GERRIT_REPLICATED_EVENTS_SEND"
  echo "GERRIT_REPLICATED_EVENTS_RECEIVE: $GERRIT_REPLICATED_EVENTS_RECEIVE"
  echo "BACKUP_DIR: $BACKUP_DIR"
  echo "SCRIPT_INSTALL_DIR: $SCRIPT_INSTALL_DIR"
  echo "FIRST_NODE: $FIRST_NODE"
fi

prereqs
check_user
check_executables
create_scratch_dir
get_config_from_user
create_backup
write_new_config
cleanup
