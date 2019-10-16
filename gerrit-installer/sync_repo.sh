#!/bin/bash --noprofile
set -o pipefail
set -e

myname=${0##*/}
tf=/tmp/${myname}_$$
trap "eval rm -f ${tf}\*; exit 1" 0 1 2 15

typeset -i reposInCurBatch=0
typeset -i numErrs=0
typeset -i reposProcessed=0
typeset -i reposAdded=0
typeset -i reposAlreadyInGitMS=0
typeset -i pathsSkipped=0
typeset -i maxReposInBatch=100
typeset -i secsSleepForTaskCompletion=10
typeset -i verbose=0
typeset -i debug=0
typeset -i noChanges=0

function perr() {
  (( numErrs=numErrs+1 ))
  echo "${myname}: ERROR: $*" 1>&2
}

function ts() {
  date '+%Y%m%dT%H%M%S'
}

function ptsmsg() {
  echo "$(ts) $*"
}

function pverbose() {
  if [[ $verbose -ne 0 ]]; then
    ptsmsg "$*"
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

function die() {
  echo "FATAL: $1"
  exit 1
}

function sanity_checks() {
  # Check for required binaries:
  if ! xmllint --testIO </dev/null >/dev/null 2>&1; then
    perr "Must have xmllint in your path"
  fi
  if ! curl --help </dev/null >/dev/null 2>&1; then
    perr "Must have xmllint in your path"
  fi

  # Check if anything failed:
  if [[ $numErrs -ne 0 ]]; then
    die "Please remedy issues above and re-run."
  fi
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

function get_secure_string() {
  while true
  do
    read -s -p "$1: " INPUT
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
  APPLICATION_PROPERTIES=$(git config core.gitmsconfig ; exit 0)

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

  if [[ -z "$GITMS_ROOT" ]]; then
    ## Locate GitMS based on the property core.gitmsconfig in ~/.gitconfig
    ## If it cannot be found, prompt for its location
    find_gitms

    while true
    do
      read -e -p " Git Multisite root directory (Default: \"${GITMS_ROOT:-none}\"): " INPUT
      if [ -z "$INPUT" ]; then
        INPUT=$GITMS_ROOT
      fi

      if [[ -d "$INPUT" && -r "$INPUT" ]]; then
        ## The directory exists, but we must ensure it has an application.properties file
        APPLICATION_PROPERTIES="${INPUT}/replicator/properties/application.properties"
        APPLICATION_PROPERTIES=$(sanitize_path "$APPLICATION_PROPERTIES")

        if [ ! -e "$APPLICATION_PROPERTIES" ]; then
          echo "" 1>&2
          perr "$APPLICATION_PROPERTIES cannot be found"
          echo "" 1>&2
          continue
        fi

        break
      else
        echo "" 1>&2
        perr "directory \"$INPUT\" does not exist or is not readable"
        echo "" 1>&2
      fi
    done
    GITMS_ROOT="$INPUT"
  else
    # provided by either environment or command line option
    # sanitize and validate
    APPLICATION_PROPERTIES="${GITMS_ROOT}/replicator/properties/application.properties"
    APPLICATION_PROPERTIES=$(sanitize_path "$APPLICATION_PROPERTIES")
    if [ ! -e "$APPLICATION_PROPERTIES" ]; then
      echo "" 1>&2
      perr "$APPLICATION_PROPERTIES cannot be found"
      die "Please specify a valid GITMS_ROOT and re-run"
    fi
  fi

  echo "Using GitMS Root: $GITMS_ROOT"
}

function read_application_properties() {
  check_file "$APPLICATION_PROPERTIES"
  
  if ! SSL_ENABLED=$(fetch_property "$APPLICATION_PROPERTIES" "ssl.enabled"); then
    if [[ "$SSL_ENABLED" == "$fp_novaluefound" || "$SSL_ENABLED" == "$fp_badvalue" ]]; then
      die "Could not read application property ssl.enabled not found or bad value."
    fi
  fi
  if ! REST_PORT=$(fetch_property "$APPLICATION_PROPERTIES" "jetty.http.port"); then
    if [[ "$REST_PORT" == "$fp_novaluefound" || "$REST_PORT" == "$fp_badvalue" ]]; then
      die "Could not read application property jetty.http.port not found or bad value."
    fi
  fi
  if ! SSL_REST_PORT=$(fetch_property "$APPLICATION_PROPERTIES" "jetty.https.port"); then
    if [[ "$SSL_REST_PORT" == "$fp_novaluefound" || "$SSL_REST_PORT" == "$fp_badvalue" ]]; then
      die "Could not read application property jetty.https.port not found or bad value."
    fi
  fi
  if ! GITMS_RPGROUP_ID=$(fetch_property "$APPLICATION_PROPERTIES" "gerrit.rpgroupid"); then
    if [[ "$GITMS_RPGROUP_ID" == "$fp_novaluefound" || "$GITMS_RPGROUP_ID" == "$fp_badvalue" ]]; then
      die "Could not read application property gerrit.rpgroupid not found or bad value."
    fi
  fi
  if ! GERRIT_ROOT=$(fetch_property "$APPLICATION_PROPERTIES" "gerrit.root"); then
    if [[ "$GERRIT_ROOT" == "$fp_novaluefound" || "$GERRIT_ROOT" == "$fp_badvalue" ]]; then
      die "Could not read application property gerrit.root not found or bad value."
    fi
  fi
  if [[ $numErrs -ne 0 ]]; then
    die "Critical properties are missing, please correct before re-running"
  fi
}

function get_gitms_credentials() {
  if [ -z "$GITMS_USERNAME" ]; then
    GITMS_USERNAME=$(get_string "GitMS Username")
  else
    echo "Using GitMS Username: $GITMS_USERNAME"
  fi

  if [ -z "$GITMS_PASSWORD" ]; then
    GITMS_PASSWORD=$(get_secure_string "GitMS Password")
    echo
  else
    echo "Using GitMS Password: $GITMS_PASSWORD"
  fi
}

function fetch_config() {
  get_application_properties
  read_application_properties
  
  # do not bother getting credentials if doing a dry run.
  if [[ $noChanges == 1 ]]; then
        return
  else 
  	get_gitms_credentials
  	
  	if [ "$SSL_ENABLED" == "true" ]; then
      GITMS_URL="https://127.0.0.1:${SSL_REST_PORT}"
    else
      GITMS_URL="http://127.0.0.1:${REST_PORT}"
    fi
    
    if [ -z "$GITMS_RPGROUP_ID" ]; then
    	  die "Could not find replication group ID property (gerrit.rpgroupid) for deployment in $APPLICATION_PROPERTIES"
  	fi
  fi
  
}

function wait_for_all_tasks() {
  local tasks_url="${GITMS_URL}/api/tasks"
  typeset -i activeTasks=1
  typeset -i numWaits=0

  while (( $activeTasks != 0 )); do
    rm -f ${tf}_wfat
    if ! curl -kfsu "$GITMS_USERNAME:$GITMS_PASSWORD" -o ${tf}_wfat -H "Content-Type: application/xml" "$tasks_url"; then
      die "curl for tasks failed!"
    fi
    activeTasks=0
    if grep -q '<isDone>false</isDone>' ${tf}_wfat; then
      activeTasks=$(xmllint --format ${tf}_wfat | grep '<isDone>false</isDone>' | wc -l)
    fi
    if (( $activeTasks != 0 )); then
      ptsmsg "Waiting for $activeTasks tasks to complete"
      (( numWaits+=1 ))
      sleep $secsSleepForTaskCompletion
    fi
  done
  rm -f ${tf}_wfat
  if [[ $numWaits -ne 0 ]] || [[ $verbose -ne 0 ]]; then
    ptsmsg "All tasks completed (waited $numWaits times for ${secsSleepForTaskCompletion}s each wait)"
  fi
}

function add_repo() {
  local repoPath=$1
  local repoName=${repoPath%.git}

  (( reposProcessed+=1 ))
  if [ -z "$repoPath" ]; then
    die "add_repo: No path supplied"
  fi

  repoName=${repoName#$GERRIT_GIT_BASE}
  repoName=$(sanitize_path $repoName | sed -e 's:^/*::')

  local encodedRepoPath=$(urlencode "$repoPath")

  ptsmsg "Adding \"$repoName\":"

  local search_url="${GITMS_URL}/api/repository/search?filesystemPath=${encodedRepoPath}"
  local deploy_url="${GITMS_URL}/api/repository?replicationGroupId=${GITMS_RPGROUP_ID}&gerritRepo=true&timeout=60"

  if curl -kfsu "$GITMS_USERNAME:$GITMS_PASSWORD" -o /dev/null "$search_url"; then
    (( reposAlreadyInGitMS+=1 ))
    echo "    Not adding \"$repoName\": already exists in Git Multisite"
    return 0
  fi

  rm -f ${tf}_ar
  local xml=$(printf '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><git-repository><fileSystemPath>%s</fileSystemPath><name>%s</name><replicationGroupId>%s</replicationGroupId></git-repository>' "$repoPath" "$repoName" "$GITMS_RPGROUP_ID")
  if curl -ksu "$GITMS_USERNAME:$GITMS_PASSWORD" -X POST -H "Content-Type: application/xml" -d @- "$deploy_url" -w '\n%{http_code}\n' <<< "$xml" > ${tf}_ar; then
    local retcode=$(tail -1 ${tf}_ar)
    case $retcode in
    2??)
      echo "    Added $repoName"
      (( reposAdded+=1 ))
      (( reposInCurBatch+=1 ))
      ;;
    *)
      perr "Could not add repository $repoPath (server returned $retcode)"
      typeset -i outLines=$(wc -l < ${tf}_ar)
      (( outLines-=1 ))
      head -${outLines} ${tf}_ar 1>&2
      ;;
    esac
  else
    perr "Could not add repository $repoPath (curl returned $?)"
  fi
  rm -f ${tf}_ar

  if [[ $reposInCurBatch -ge $maxReposInBatch ]]; then
    wait_for_all_tasks
    reposInCurBatch=0
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

  rm -f ${tf}_sr
  if ! find "$GERRIT_GIT_BASE" -type f -a -name 'config' | sort > ${tf}_sr; then
    die "'find $GERRIT_GIT_BASE ...' failed: $?"
  fi
  while read configPath; do
    # Verify that the found repository is a valid bare repo before adding:
    typeset -x GIT_CONFIG="${configPath}"
    if ! repoType=$(git config --get core.bare 2>/dev/null); then
      (( pathsSkipped+=1 ))
      pverbose "git config failed, skipping $configPath"
      continue
    fi
    if [[ "$repoType" != "true" ]]; then
      (( pathsSkipped+=1 ))
      pverbose "git core.bare not true, skipping $configPath"
      continue
    fi
    repoPath=${configPath%/config}      # in bare repos, the config is always in the repo directory
    # additional sanity check: is there a "refs" directory?
    if [[ ! -d "${repoPath}/refs" ]]; then
      (( pathsSkipped+=1 ))
      pverbose "no sibling \"refs\" directory, skipping $configPath"
      continue
    fi
    if [[ $noChanges == 1 ]]; then
        echo "Would add $repoPath"
    else
        add_repo "$repoPath"
    fi
  done < ${tf}_sr
  rm -f ${tf}_sr

  if [[ $noChanges == 1 ]]; then
    # Check for case of directory ending in ".git" but not a git repo:
    if ! find "$GERRIT_GIT_BASE" -type d -a -name '*.git' | sort > ${tf}_sr; then
      die "'find $GERRIT_GIT_BASE ...' failed: $?"
    fi
    while read aDir; do
      cFile="${aDir}/config"
      if [[ ! -f "${cFile}" ]]; then
        echo "Found non-Git repo: $cFile"
      fi
    done < ${tf}_sr
    rm -f ${tf}_sr
  fi

  return 0
}

function check_file() {
  file=$1
  
  # Check the value passed in exists
  if [[ ! -e "$file" ]]; then
    die "File \"$file\" does not exist, aborting"
  fi

  # Check the value passed in is actually a file
  if [[ ! -f "$file" ]]; then
    die "File \"$file\" is not a file, aborting"
  fi

  # Check the file is readable
  if [[ ! -r "$file" ]]; then
    die "Cannot read file \"$file\", aborting"
  fi

  return 0;
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
        perr "Space found in property Key or Value (\"$key\"=\"$value\")"
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for property key or value being empty
      if [[ -z "$key" ]] || [[ -z "$value" ]]; then
        perr "Missing property Key / Value, (\"$key\"=\"$value\")"
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for key having any illegal character(s) in it
      if echo "$key" |  grep -q ${illegalKeyChars}; then
        perr "Illegal character(s) ${illegalKeyChars} in property Key, (\"$key\"=\"$value\")"
        echo "$fp_badvalue"
        exit 1
      fi

      # Check for value having any illegal characters(s) in it
      if echo "$value" | grep -q ${illegalValChars}; then
        perr "Illegal character(s) ${illegalValChars} in property Value, (\"$key\"=\"$value\")"
        echo "$fp_badvalue"
        exit 1
      fi

      echo "$value"
      exit 0
    fi
  done < "$file"

  perr "Could not find property \"$property\" in file \"$file\"" 1>&2
  echo "$fp_novaluefound"
  exit 1
}

function print_help {
  usage="Usage: $myname
      -h  Show this help
      -m  Max repositories added before checking tasks are all done (default $maxReposInBatch)
      -n  Do not add repos to GitMS (just identify possibilities)
      -u  GitMS username
      -p  GitMS password
      -r  Path of git-multisite install directory (GITMS_ROOT)
      -s  Seconds to sleep waiting for tasks to complete (default $secsSleepForTaskCompletion)
      -v  Verbose output
      "
  echo "$usage"
  exit
}

while getopts D:m:np:r:s:u:vh opt
do
  case "$opt" in
    D) debug="$OPTARG";;
    m) maxReposInBatch="$OPTARG";;
    n) noChanges=1;;
    r) GITMS_ROOT="$OPTARG";;
    s) secsSleepForTaskCompletion="$OPTARG";;
    u) GITMS_USERNAME="$OPTARG";;
    p) GITMS_PASSWORD="$OPTARG";;
    h) print_help;;
    v) verbose=1;;
  esac
done

sanity_checks
fetch_config
scan_repos

xtramsg=""
if [[ $verbose -eq 0 ]]; then
  xtramsg=" (turn on verbose to see them)"
fi

echo ""
echo "Repository deployment completed"
echo "    There were $reposProcessed repositories processed"
echo "    There were $reposAlreadyInGitMS already in GitMS"
echo "    There were $reposAdded repositories added"
echo "    There were $pathsSkipped paths skipped${xtramsg}"
echo "    There were $numErrs errors seen"

eval rm -f ${tf}*
trap - 0 1 2 15

if [[ $numErrs -ne 0 ]]; then
  exit 1
fi
exit 0