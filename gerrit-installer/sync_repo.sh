#!/bin/bash --noprofile
set -o pipefail
set -e

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

function die() {
  echo "ERROR: $1"
  exit 1
}

## Removes double and trailing //'s from path
function sanitize_path() {
  echo "$1" | sed -e 's://*:/:g' -e 's:/$::'
}

## Reads input until the user specifies a string which is not empty
## $1: The string to display
function get_string() {

  while true
  do
    read -e -p "$1: " INPUT
    if [ ! -z "$INPUT" ]; then
      break
    fi
  done

  echo "$INPUT"
}

function find_gitms() {
  ## Make sure $HOME has been set
  if [[ -z "$home" ]]; then
    typeset -x HOME=$(echo ~)
  fi

  GIT_CONFIG="$HOME/.gitconfig"
  export GIT_CONFIG
  APPLICATION_PROPERTIES=$(git config core.gitmsconfig)

  if [ -z "$APPLICATION_PROPERTIES" ]; then
    return
  fi

  if [ ! -e "$APPLICATION_PROPERTIES" ]; then
    APPLICATION_PROPERTIES=""
    return
  fi

  GITMS_ROOT=$(sanitize_path "$APPLICATION_PROPERTIES")
  GITMS_ROOT=${GITMS_ROOT%/replicator/properties/application.properties}
}

function get_application_properties() {

  ## Locate GitMS based on the property core.gitmsconfig in ~/.gitconfig
  ## If it cannot be found, prompt for its location
  find_gitms

  if [ -z "$GITMS_ROOT" ]; then
    while true
    do
      read -e -p " Git Multisite root directory: " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$GITMS_ROOT
      fi

      if [[ -d "$INPUT" && -r "$INPUT" ]]; then
        ## The directory exists, but we must ensure it has an application.properties file
        APPLICATION_PROPERTIES="${INPUT}/replicator/properties/application.properties"
        APPLICATION_PROPERTIES=$(echo "$APPLICATION_PROPERTIES" | tr -s / )

        if [ ! -e "$APPLICATION_PROPERTIES" ]; then
          echo ""
          echo -e " \033[1mERROR:\033[0m $APPLICATION_PROPERTIES cannot be found"
          echo ""
          continue
        fi

        break
      else
        echo ""
        echo -e " \033[1mERROR:\033[0m directory does not exist or is not readable"
        echo ""
      fi
    done
    GITMS_ROOT="$INPUT"
  fi
  echo "Using GitMS Root: $GITMS_ROOT"
}


function read_application_properties() {
  if ! SSL_ENABLED=$(fetch_property "$APPLICATION_PROPERTIES" "ssl.enabled"); then
    if [[ "$SSL_ENABLED" == "$fp_novaluefound" || "$SSL_ENABLED" == "$fp_badvalue" ]]; then
      unset SSL_ENABLED
      exit 2
    fi
  fi
  if ! REST_PORT=$(fetch_property "$APPLICATION_PROPERTIES" "jetty.http.port"); then
    if [[ "$REST_PORT" == "$fp_novaluefound" || "$REST_PORT" == "$fp_badvalue" ]]; then
      unset REST_PORT
      exit 2
    fi
  fi
  if ! SSL_REST_PORT=$(fetch_property "$APPLICATION_PROPERTIES" "jetty.https.port"); then
    if [[ "$SSL_REST_PORT" == "$fp_novaluefound" || "$SSL_REST_PORT" == "$fp_badvalue" ]]; then
      unset SSL_REST_PORT
      exit 2
    fi
  fi
  if ! GITMS_RPGROUP_ID=$(fetch_property "$APPLICATION_PROPERTIES" "gerrit.rpgroupid"); then
    if [[ "$GITMS_RPGROUP_ID" == "$fp_novaluefound" || "$GITMS_RPGROUP_ID" == "$fp_badvalue" ]]; then
      unset GITMS_RPGROUP_ID
      exit 2
    fi
  fi
  if ! GERRIT_ROOT=$(fetch_property "$APPLICATION_PROPERTIES" "gerrit.root"); then
    if [[ "$GERRIT_ROOT" == "$fp_novaluefound" || "$GERRIT_ROOT" == "$fp_badvalue" ]]; then
      unset GERRIT_ROOT
      exit 2
    fi
  fi
}

function get_gitms_credentials() {
  if [ -z "$GITMS_USERNAME" ]; then
    GITMS_USERNAME=$(get_string "GitMS Username")
  else
    echo "Using GitMS Username: $GITMS_USERNAME"
  fi

  if [ -z "$GITMS_PASSWORD" ]; then
    GITMS_PASSWORD=$(get_string "GitMS Password")
  else
    echo "Using GitMS Password: $GITMS_PASSWORD"
  fi
}

function fetch_config() {
  get_application_properties
  read_application_properties
  get_gitms_credentials

  if [ -z "$GITMS_RPGROUP_ID" ]; then
    echo "ERROR: Could not find replication group ID property (gerrit.rpgroupid) for deployment in $APPLICATION_PROPERTIES"
  fi

  if [ "$SSL_ENABLED" == "true" ]; then
    GITMS_URL="https://127.0.0.1:${SSL_REST_PORT}"
  else
    GITMS_URL="http://127.0.0.1:${REST_PORT}"
  fi
}

function add_repo() {
  local repoPath=$1
  local repoName=${repoPath%.git}

  if [ -z "$repoPath" ]; then
    echo -e "ERROR: add_repo: No path supplied"
    exit 1;
  fi

  repoName=${repoName#$GERRIT_GIT_BASE}
  repoName=$(sanitize_path $repoName | sed -e 's:^/*::')

  local encodedRepoPath=$(urlencode "$repoPath")

  echo "Adding $repoName: "

  local search_url="${GITMS_URL}/api/repository/search?filesystemPath=${encodedRepoPath}"
  local deploy_url="${GITMS_URL}/api/repository?replicationGroupId=${GITMS_RPGROUP_ID}&gerritRepo=true&timeout=60"

  if curl -kfsu "$GITMS_USERNAME:$GITMS_PASSWORD" -o /dev/null "$search_url"; then
    echo "Not adding $repoName, which already exists in Git Multisite"
    return 0
  fi

  local xml=$(printf '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><git-repository><fileSystemPath>%s</fileSystemPath><name>%s</name><replicationGroupId>%s</replicationGroupId></git-repository>' "$repoPath" "$repoName" $GITMS_RPGROUP_ID)
  echo "$xml" | curl -ku "$GITMS_USERNAME:$GITMS_PASSWORD" -X POST -H "Content-Type: application/xml" -d @- "$deploy_url"

  if [ "$?" -ne 0 ]; then
    echo "ERROR: Could not add repository $repoPath"
  else
    echo "Added $repoName"
  fi
}

function scan_repos() {

  local repoPath

  GIT_CONFIG="$GERRIT_ROOT/etc/gerrit.config"
  export GIT_CONFIG
  GERRIT_GIT_BASE=$(git config --get gerrit.basepath || true)
  GERRIT_GIT_BASE=${GERRIT_GIT_BASE:-git}

  if [[ "$GERRIT_GIT_BASE" != /* ]]
  then
     #Assumes relative path if GERRIT_GIT_BASE doesn't start with '/'
     GERRIT_GIT_BASE="$GERRIT_ROOT/$GERRIT_GIT_BASE"
  fi

  [ ! -e "$GERRIT_GIT_BASE" ] && die "$GERRIT_GIT_BASE does not exist"
  [ ! -d "$GERRIT_GIT_BASE" ] && die "$GERRIT_GIT_BASE is not a directory"

  echo ""
  echo "Scanning for repositories in $GERRIT_GIT_BASE"
  echo ""

  find "$GERRIT_GIT_BASE" -type d -name '*.git' | while read repoPath; do
    add_repo "$repoPath"
  done
  return 0
}

# Special return value
fp_novaluefound="NoVaLuE"
fp_badvalue="BaDvAlUe"
function fetch_property() {
  # File and Property to check are passed as arguments to function
  file=$1
  property=$2
  illegalKeyChars="[$\?\`*+%:<>]"
  illegalValChars="[$\?\`*%<>]"

  # Check if file does not exist
  if [[ ! -e "$file" ]]; then
   echo "Error: \"$file\" does not exist." 1>&2
   echo "$fp_badvalue"
   exit 2
  fi

  # Check the value passed in is actually a file
  if [[ ! -f "$file" ]]; then
    echo "Error: File \"$file\" does not exist, aborting" 1>&2
    echo "$fp_badvalue"
    exit 2
  fi

  # Check the file is readable
  if [[ ! -r "$file" ]]; then
    echo "Error: cannot read file \"$file\", aborting" 1>&2
    echo "$fp_badvalue"
    exit 2
  fi

  while IFS='=' read -r key value
  do
    # Ignore lines Starting with a comment #
    [[ $key = \#* || -z "$key" ]] && continue
    key=$(echo $key)
    value=$(echo $value)

    if [[ $key != $property ]]; then
      continue;
    else
      # Check for key or value having a space in property / value
      if echo "$key" | grep -q ' ' || echo "$value" | grep -q ' '; then
        echo "Error: space found in property Key or Value (\"$key\"=\"$value\")" 1>&2
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for property key or value being empty
      if [[ -z "$key" ]] || [[ -z "$value" ]]; then
        echo "Error: Missing property Key / Value, (\"$key\"=\"$value\")" 1>&2
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for key having any illegal character(s) in it
      if echo "$key" |  grep -q ${illegalKeyChars}; then
        echo "Error: Illegal character(s) ${illegalKeyChars} in property Key, (\"$key\"=\"$value\")" 1>&2
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for value having any illegal characters(s) in it
      if echo "$value" | grep -q ${illegalValChars}; then
        echo "Error: Illegal character(s) ${illegalValChars} in property Value, (\"$key\"=\"$value\")" 1>&2
        echo "$fp_badvalue"
        exit 1
      fi

      echo "$value"
      exit 0
    fi
  done < "$file"

  echo "Error: could not find property \"$property\" in file \"$file\"" 1>&2
  echo "$fp_novaluefound"
  exit 1
}

function print_help {
  usage="Usage: $(basename "$0")
      -h  Show this help
      -u  GitMS username
      -p  GitMS password"
  echo "$usage"
  exit
}

while getopts u:p:h opt
do
  case "$opt" in
    u) GITMS_USERNAME="$OPTARG";;
    p) GITMS_PASSWORD="$OPTARG";;
    h) print_help;;
  esac
done

fetch_config
scan_repos

echo ""
echo "Repository deployment completed"
