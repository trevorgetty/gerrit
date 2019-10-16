1. Run once git-update-submodules.sh

At the start of a new development cycle it is important that submodules are checked out and updated 
relative to the current branch.
Once gerrit has been checked out on the new branch please run git-update-submodules.sh to update the
submodules.  

Example :

./git-update-submodules.sh stable-2.13

This will checkout submodules for branch stable-2.13. "stable-2.13" is the name of the corresponding
branch that exists on the remote repo(s), where we are pulling the submodules from.
  
The user can then commit these updated submodule links.

Note well :  This ONLY needs to be run once at the start of a development cycle.

2. Updating submodules during a development cycle

When users need to update their working copy to the correct submodules, use the option :
   
   --recurse-submodules[=<pathspec>] to initialize submodules e.g.

   git clone --recurse-submodules option on a new repo.
 
 Alternatively run a normal git clone xxx and then inside repo run :
   
   git pull --recurse-submodules



