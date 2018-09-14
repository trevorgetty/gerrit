package com.google.gerrit.gerritconsoleapi.cli.processing;

import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import org.eclipse.jgit.errors.NotSupportedException;


public interface CommandItem {

  // Command Main execution methods for subcommand / help activity.
  void execute() throws LogAndExitException;
  void execute(String... arguments) throws LogAndExitException;

  void displayHelp();
}
