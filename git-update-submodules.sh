#!/bin/bash

BRANCH=$1
shift

if [ -z $BRANCH ]; then
  echo "Missing argument (branch name)";
  exit 1;
fi

echo "Checking out submodules for branch $BRANCH"
git submodule sync
git submodule init
git submodule update
git submodule foreach "(git checkout $BRANCH && git pull --ff origin $BRANCH) || true"

for i in $(git submodule foreach --quiet 'echo $path')
do
  echo "Adding $i to root repo"
  git add "$i"
done

echo "Submodules have been checked out for branch $BRANCH"
