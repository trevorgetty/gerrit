package com.google.gerrit.server.replication;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;

import java.io.File;
import java.io.FileFilter;

public class ReplicatedEventsFileFilter implements FileFilter {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final String LAST_PART=".json";
    private String firstPart;

    public ReplicatedEventsFileFilter(String firstPart){
      this.firstPart = firstPart;
    }

    @Override
    public boolean accept(File pathname) {
      //We are not interested in processing directories. If we have failed
      //events, a failed directory can be present.
      if(pathname.isDirectory()){
        return false;
      }

      if(Strings.isNullOrEmpty(pathname.getName())){
        return false;
      }

      String name = pathname.getName();
      //All event files must end in .json however event files can begin
      //with events, persisted etc
      if (name.startsWith(firstPart) && name.endsWith(LAST_PART)) {
        return true;
      }
      logger.atSevere().log("File %s is not allowed here, remove it please ", pathname);
      return false;
    }
}
