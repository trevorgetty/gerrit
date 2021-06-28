
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
 
package com.google.gerrit.common;

import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import com.wandisco.gerrit.gitms.shared.events.GerritEventData;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

  private static final Logger log = LoggerFactory.getLogger(Persister.class);
  // These values are just to do a minimal filtering
  private static final String FIRST_PART = "persisted_";
  private static final String LAST_PART = ".json";
  private static final String TMP_PART = ".tmp";
  private static final String PERSIST_FILE=FIRST_PART + "%s_%s_%s_%s" + LAST_PART;
  private static final ReplicatedEventsFileFilter persistedEventsFileFilter = new ReplicatedEventsFileFilter(FIRST_PART);
  private static final Gson gson = new Gson();
  private final File baseDir;

  public Persister(File baseDir) throws IOException {
    this.baseDir = baseDir;

    if (baseDir.exists() && !baseDir.isDirectory()) {
      throw new IOException("baseDir is not a directory: "+baseDir);
    } else if (!baseDir.exists()) {
      boolean created = baseDir.mkdirs();
      if (!created) {
        throw new IOException("Cannot create directory "+baseDir);
      }
    }
  }

  //NOTE: This method is called only once upon Persister object instance creation
  //in ReplicatedIndexEventManager.
  public <T extends Persistable> List<T> getObjectsFromPath(Class<T> clazz) {
    File[] listFiles = baseDir.listFiles(persistedEventsFileFilter);
    List<T> result = new ArrayList<>();

    if (listFiles != null) {
      Arrays.sort(listFiles);
      for (File file : listFiles) {

        try (InputStreamReader eventJson = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {

          //If the event JSON is invalid, then we want to
          T fromJson;
          try {
            fromJson = gson.fromJson(eventJson, clazz);
          }catch(JsonSyntaxException e){
            throw new InvalidEventJsonException(String.format("Event file contains Invalid JSON. \"%s\""
                , eventJson.toString() ));
          }

         if (fromJson == null) {
           log.warn("Json file {} only contained an EOF", file);
           continue;
         }

          fromJson.setPersistFile(file);
          result.add(fromJson);
        } catch (IOException | JsonSyntaxException | InvalidEventJsonException e) {
          log.error("PR Could not decode json file {}", file, e);
          moveFileToFailed(baseDir, file);
        }
      }
    }
    return result;
  }

  static void moveFileToFailed(final File directory, final File file) {
    File failedDir = new File(directory,"failed");
    if (!failedDir.exists()) {
      boolean mkdirs = failedDir.mkdirs();
      if (!mkdirs) {
        log.error("Could not create directory for failed directory: " + failedDir.getAbsolutePath());
        return;
      }
    }

    boolean renameOp = file.renameTo(new File(failedDir,file.getName()));

    if (!renameOp) {
        log.error("Could not move file in failed directory: " + file);
    }

  }

  boolean deleteFileIfPresentFor(Persistable p) {
    File persistFile = p.getPersistFile();
    if (persistFile != null) {
      return persistFile.delete();
    }
    return false;
  }

  boolean deleteFileFor(Persistable p) {
    File persistFile = p.getPersistFile();
    if (persistFile == null) {
      throw new IllegalStateException("Cannot delete file of not-yet persisted object");
    }
    return persistFile.delete();
  }

  void persistIfNotAlready(T obj, String projectName) throws IOException {
    if (!obj.hasBeenPersisted()) {
      persist(obj, projectName);
    }
  }

  public void persist(T obj, String projectNameSha) throws IOException {
    final String json = gson.toJson(obj)+'\n';

    File tempFile = File.createTempFile(FIRST_PART, TMP_PART, baseDir);
    try (FileOutputStream writer = new FileOutputStream(tempFile,true)) {
      //writing event to .tmp file
      writer.write(json.getBytes(StandardCharsets.UTF_8));
    }

    //Creating a GerritEventData object from the inner event JSON of the EventWrapper object.
    GerritEventData eventData =
        ObjectUtils.createObjectFromJson(json, GerritEventData.class);

    if(eventData == null){
      log.error("Unable to set event filename, could not create "
          + "GerritEventData object from JSON {}", json);
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
        ObjectUtils.getHexStringOfLongObjectHash(Long.parseLong(eventData.getEventNanoTime())) : Replicator.DEFAULT_NANO;
    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);
    String objectHash = ObjectUtils.getHexStringOfIntObjectHash(json.hashCode());

    //The persisted events file is the same as an event file except it starts with persisted instead
    //of event. The format of the file will be persisted_<eventTimestamp>x<eventNanoTime>_<nodeId>_<projectNameSha1>_<hashcode>.json
    File persistFile = new File(baseDir, String.format(PERSIST_FILE,
        eventTimeStr, eventData.getNodeIdentity(), getProjectNameSha1(projectNameSha), objectHash));

    //We then rename the file from persisted_<randomDigit>.tmp to the persisted event file
    boolean done = tempFile.renameTo(persistFile);

    if(!done){
      log.error("Unable to rename file {} to {}", tempFile, persistFile);
      throw new IOException(String.format("PR Unable to rename file %s to %s", tempFile, persistFile));
    }
    //We have renamed the file so we can now set the persist file.
    obj.setPersistFile(persistFile);
  }

}
