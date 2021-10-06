package com.google.gerrit.common;

import com.google.common.base.Strings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;

/**
 * File filter class, to assist searching in a directory for files which match
 * your own custom filtering.
 * N.B. You can recursively search but we do not in this filter.
 */
public class ReplicatedEventsFileFilter implements FileFilter {

  private static final String allowedExtension=".json";
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

    // We want to screen out .tmp files and other suffix and ignore them from sort.
    // Only accept real events files which match... We can silent ignore the other suffix.
    // Ensure we do allow files with prefix and extension e.g. events_XXX.json.
    if (name.startsWith(firstPart)) {
      return name.endsWith(allowedExtension);
    }

    // Event files found in directory not beginning events_ ???
    // There used to be old persisted_ events which we no longer allow here, they only
    // exist in the failed directory - remove them!!
    log.error("File \"{}\" is not allowed here, remove it please ",pathname);
    return false;
  }

}
