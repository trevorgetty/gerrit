
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

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileFilter;
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
  private static final String PERSIST_FILE=FIRST_PART + "%s_%s_%s" + LAST_PART;
  private static final ReplicatedEventsFileFilter fileFilter = new ReplicatedEventsFileFilter(FIRST_PART);
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
    File[] listFiles = baseDir.listFiles(fileFilter);
    List<T> result = new ArrayList<>();

    if (listFiles != null) {
      Arrays.sort(listFiles);
      for (File file : listFiles) {

        try (InputStreamReader fileToRead = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {
          T fromJson = gson.fromJson(fileToRead,clazz);
         if (fromJson == null) {
           log.warn("Json file {} only contained an EOF", file);
           continue;
         }

          fromJson.setPersistFile(file);
          result.add(fromJson);
        } catch (IOException | JsonSyntaxException e) {
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
    final String msg = gson.toJson(obj)+'\n';

    File tempFile = File.createTempFile(FIRST_PART, TMP_PART, baseDir);
    try (FileOutputStream writer = new FileOutputStream(tempFile,true)) {
      //writing event to .tmp file
      writer.write(msg.getBytes(StandardCharsets.UTF_8));
    }

    String [] jsonData = Replicator.ParseEventJson.jsonEventParse(msg);

    //If there is a problem with the events within the event file for any reason then get out early
    if(jsonData == null || jsonData.length <= 0){
      log.error("jsonData was null or empty. Could not persist events file as it contained no events!");
      //Note: if we get in here then the temp file will be left behind.
      return;
    }

    //The persisted events file is the same as an event file except it starts with persisted instead
    //of event. The format of the file will be persisted_timestamp_nodeId_projectNameSha1.json
    File persistFile = new File(baseDir, String.format(PERSIST_FILE,
        jsonData[0], jsonData[1], getProjectNameSha1(projectNameSha)));

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
