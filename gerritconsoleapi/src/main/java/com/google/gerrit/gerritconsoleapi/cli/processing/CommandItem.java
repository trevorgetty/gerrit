
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.gerritconsoleapi.cli.processing;

import com.google.gerrit.gerritconsoleapi.exceptions.LogAndExitException;
import org.eclipse.jgit.errors.NotSupportedException;


public interface CommandItem {

  // Command Main execution methods for subcommand / help activity.
  void execute() throws LogAndExitException;
  void execute(String... arguments) throws LogAndExitException;

  void displayHelp();
}
