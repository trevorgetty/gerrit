#!/bin/bash --noprofile

#
# Parse the VERSION file in the root of Gerrit repo.
# We want this to be executed from the root of the repo, if not update our location to be as if we were!
#
# Allow users to supply a gerrit repo root, incase they aren't running in the directory. 
GERRIT_REPO_ROOT=$1

function check_in_repo_root
{
	# There should be a BUCK file in the root
	# And a VERSION file to indicate the version number we are to use. 
	# And a build directory for this script to live in.
	local GERRIT_REPO_ROOT_TMP=$PWD
	
	if [[ -z $GERRIT_REPO_ROOT ]]; then
		# Someone hasn't supplied a repo root, default to pwd, its best we can do.
		# Check if we are in the build folder, with our script in it, then default to parent directory.
			# Check if version file is in root.
	  if [[ -f "$PWD/get_version_number.sh" ]]; then
	    # Ok we are in the build directory, use the parent.
	    GERRIT_REPO_ROOT_TMP="$PWD/../"
	  elif [[ -f "$PWD/build/get_version_number.sh" ]]; then
	    GERRIT_REPO_ROOT_TMP="$PWD"
	  else
	    # leave as current folder, and let it complain if they are off somewhere silly.
	    GERRIT_REPO_ROOT_TMP=$PWD
	  fi

	else
		# Someone has supplied a repo root location, lets use it instead.
		GERRIT_REPO_ROOT_TMP=$GERRIT_REPO_ROOT
	fi

	# Perform checks listed above. 
	
	# Start by checking the build sub directory.
	if [[ -d "$GERRIT_REPO_ROOT_TMP/build" && "$GERRIT_REPO_ROOT_TMP/build" ]]; then
	  # Check if version file is in root.
	  check_file_exists $GERRIT_REPO_ROOT_TMP/VERSION

	  # Check BUCK file exists for root command
	  check_file_exists $GERRIT_REPO_ROOT_TMP/BUCK

	  export GERRIT_REPO_ROOT=$GERRIT_REPO_ROOT_TMP
	else
		# It isn't a directory, so exit!
		die "Not in the valid Gerrit Repository Root -> Please run your command from that directory, or pass it in as an argument to the script e.g. './get_version_number.sh ./gerrit'"
	fi

}

function check_file_exists()
{
	local file=$1
	if [[ -z $file ]]; then
		die "No valid file location has been specified."
	fi
	
	if [[ ! -f $file ]]; then
		die "Valid File was not found for required repo item: $file"
	fi
}

function die()
{
	echo "ERROR: Exiting build script due to: $1"
	exit 1
}

function read_version ()
{
	local VERSION_FILE=$1
	if [[ -z $VERSION_FILE ]]; then
		die "Invalid use of function read_version, as a version file must be supplied."
	fi

	local TMP_VERSION="$(grep GERRIT_VERSION $VERSION_FILE | awk '{print $NF}')"
	echo $TMP_VERSION
}

#Start execution now.
# Check we are running in the gerrit repo location, or we have been given where it is. 
check_in_repo_root

# Export a new Variable called GERRIT_VERSION so it can be used on command line. 
export GERRIT_VERSION=$(read_version $GERRIT_REPO_ROOT/VERSION)
export GERRIT_VERSION=${GERRIT_VERSION//\'/}
echo $GERRIT_VERSION

