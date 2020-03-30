package com.google.gerrit.common;

import com.google.common.base.Strings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;

public class ReplicatedEventsFileFilter implements FileFilter {

  private static final String LAST_PART=".json";
  private static final String allowedExtension = ".tmp";
  private static final Logger log = LoggerFactory.getLogger(ReplicatedEventsFileFilter.class);
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

    // We want to screen out .tmp files as it may be the case that these
    // files may exist in the directory at the time of the gerrit poll.
    // We also don't want the file array populated with .tmp files either
    // as this will affect the sort.
    if (name.startsWith(firstPart) && name.endsWith(allowedExtension)){
      return false;
    }

    //All event files must end in .json however event files can begin
    //with events, persisted etc
    if (name.startsWith(firstPart) && name.endsWith(LAST_PART)) {
        return true;
    }
    log.error("File \"{}\" is not allowed here, remove it please ",pathname);
    return false;
  }

}
