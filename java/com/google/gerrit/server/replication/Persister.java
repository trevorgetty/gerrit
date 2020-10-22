
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.server.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.wandisco.gerrit.gitms.shared.events.GerritEventData;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.DEFAULT_NANO;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.EVENT_FILE_NAME_FORMAT;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.PERSISTED_EVENT_FILE_PREFIX;
import static com.wandisco.gerrit.gitms.shared.events.filter.EventFileFilter.TEMPORARY_EVENT_FILE_EXTENSION;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;


/**
 * Utility to make an object be persisted on the filesystem
 *
 * Ok, so this class must have these characteristics
 * - easily configure the path to where persist the values
 * - easily mark the object as persisted
 * - easily reload the objects from the path and return an array
 * - easily delete the persisted file when needed
 *
 * The overall idea is that you can:
 * - save some object in a directory,
 * - assign to that object the filename which this persister has saved the file in
 * - load in a list all the objects that are in a directory
 * - remove the file after you don't need that anymore
 *
 * @author antonio
 * @param <T> the type of the object you want to persist
 */
public class Persister<T extends Persistable> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Gson gson = new Gson();
  private final File baseDir;

  private static final EventFileFilter fileFilter = new EventFileFilter(PERSISTED_EVENT_FILE_PREFIX);

  private static final long GC_NOW = 0;
  private static long lastGCTime = 0;

  public Persister(File baseDir) throws IOException {
    this.baseDir = baseDir;

    if (baseDir.exists() && !baseDir.isDirectory()) {
      throw new IOException("baseDir is not a directory: "+baseDir);
    } else if (!baseDir.exists()) {
      // no need to run GC here because we are about to
      // create the directory where it GC's the files from.
      boolean created = baseDir.mkdirs();
      if (!created) {
        throw new IOException("Cannot create directory "+baseDir);
      }
    } else {
      // On startup start with a clean incoming persisted directory
      gcOldPersistedFiles(GC_NOW);
    }

  }

  public <T extends Persistable> List<T> getObjectsFromPath(Class<T> clazz) {
    File[] listFiles = baseDir.listFiles(fileFilter);
    List<T> result = new ArrayList<>();

    if (listFiles != null) {
      Arrays.sort(listFiles);
      for (File file : listFiles) {
        try (InputStreamReader eventJson = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {
          //If the event JSON is invalid, then we want to throw
          T fromJson;
          try {
            fromJson = gson.fromJson(eventJson, clazz);
          } catch(JsonSyntaxException e) {
            throw new InvalidEventJsonException(String.format("Event file contains Invalid JSON: \"%s\"",
                                                              eventJson.toString()), e);
          }

         if (fromJson == null) {
           logger.atWarning().log("Json file {} only contained an EOF", file);
           continue;
         }

          fromJson.setPersistFile(file);
          result.add(fromJson);
        } catch (IOException | JsonSyntaxException | InvalidEventJsonException e) {
          logger.atSevere().log("PR Could not decode json file {}", file, e);
          moveFileToFailed(baseDir, file);
        }
      }
    }
    return result;
  }

  public static boolean moveFileToFailed(final File directory, final File file) {
    boolean result = false;
    File failedDir = new File(directory,"failed");
    if (!failedDir.exists()) {
      boolean mkdirs = failedDir.mkdirs();
      if (!mkdirs) {
        logger.atSevere().log("Could not create directory for failed directory: " + failedDir.getAbsolutePath());
      }
    }

    result = file.renameTo(new File(failedDir,file.getName()));

    if (!result) {
        logger.atSevere().log("Could not move file in failed directory: " + file);
      }

    return result;
  }

  public boolean deleteFileIfPresentFor(Persistable p) {
    File persistFile = p.getPersistFile();
    if (persistFile != null) {
      return persistFile.delete();
    }
    return false;
  }

  public boolean deleteFileFor(Persistable p) {
    File persistFile = p.getPersistFile();
    if (persistFile == null) {
      throw new IllegalStateException("Cannot delete file of not-yet persisted object");
    }
    return persistFile.delete();
  }

  public void persistIfNotAlready(T obj, String projectName) throws IOException {
    if (!obj.hasBeenPersisted()) {
      persist(obj, projectName);
    }
  }

  public void persist(T obj, String projectName) throws IOException {
    final String json = gson.toJson(obj)+'\n';

    File tempFile = File.createTempFile(PERSISTED_EVENT_FILE_PREFIX, TEMPORARY_EVENT_FILE_EXTENSION, baseDir);
    try (FileOutputStream writer = new FileOutputStream(tempFile,true)) {
      writer.write(json.getBytes(StandardCharsets.UTF_8));
    }

    //Creating a GerritEventData object from the inner event JSON of the EventWrapper object.
    GerritEventData eventData = ObjectUtils.createObjectFromJson(json, GerritEventData.class);

    if(eventData == null){
      logger.atSevere().log("Unable to set event filename, could not create GerritEventData object from JSON {}", json);
      return;
    }

    String eventTimestamp = eventData.getEventTimestamp();
    // The java.lang.System.nanoTime() method returns the current value of
    // the most precise available system timer, in nanoseconds. The value returned represents
    // nanoseconds since some fixed but arbitrary time (in the future, so values may be negative)
    // and provides nanosecond precision, but not necessarily nanosecond accuracy.

    // The long value returned will be represented as a padded hexadecimal value to 16 digits in order to have a
    //guaranteed fixed length as System.nanoTime() varies in length on each OS.
    // If we are dealing with older event files where eventNanoTime doesn't exist as part of the event
    // then we will set the eventNanoTime portion to 16 zeros, same length as a nanoTime represented as HEX.
    String eventNanoTime = eventData.getEventNanoTime() != null ?
                           ObjectUtils.getHexStringOfLongObjectHash(Long.parseLong(eventData.getEventNanoTime())) : DEFAULT_NANO;
    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);
    String objectHash = ObjectUtils.getHexStringOfIntObjectHash(json.hashCode());

    //The persisted events file is the same as an event file except it starts with persisted instead
    //of event. The format of the file will be persisted_<eventTimestamp>x<eventNanoTime>_<nodeId>_<projectNameSha1>_<hashcode>.json
    File persistFile = new File(baseDir, String.format(EVENT_FILE_NAME_FORMAT,
                                                       PERSISTED_EVENT_FILE_PREFIX,
                                                       eventTimeStr,
                                                       eventData.getNodeIdentity(),
                                                       getProjectNameSha1(projectName),
                                                       objectHash));


    boolean done = tempFile.renameTo(persistFile);
    if (done) {
      obj.setPersistFile(persistFile);
    } else {
      IOException e = new IOException(String.format("PR Unable to rename file %s to %s", tempFile, persistFile));
      logger.atSevere().log("Unable to rename file", e);
      throw e;
    }
  }

  public void gcOldPersistedFiles(final long lingerTime){

    long currentTime = System.currentTimeMillis();

    if (lastGCTime + lingerTime < currentTime) {
      // Time to perform a GC of the old persisted files
      // If they have hung around for longer than the lingerTime get rid.
      for (File fileEntry : baseDir.listFiles()) {
        if (fileEntry.lastModified() + lingerTime < currentTime) {
          fileEntry.delete();
        }
      }

      lastGCTime = currentTime;
    }
  }
}
