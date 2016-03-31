package com.google.gerrit.common;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
  private final PersisterFileFilter fileFilter = new PersisterFileFilter();
  private static final Gson gson = new Gson();
  private final File baseDir;
  // These values are just to do a minimal filtering
  public static final String FIRST_PART = "Pers-";
  public static final String LAST_PART = ".json";
  public static final String TMP_PART = ".tmp";

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
  
  public <T extends Persistable> List<T> getObjectsFromPath(Class<T> clazz) {
    File[] listFiles = baseDir.listFiles(fileFilter);
    List<T> result = new ArrayList<>();
    
    if (listFiles != null) {
      for (File file : listFiles) {
        try (InputStreamReader fileToRead = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {
          T fromJson = gson.fromJson(fileToRead,clazz);
          fromJson.setPersistFile(file);
          result.add(fromJson);
        } catch (IOException e) {
          log.error("PR Could not decode json file {}", file, e);
        }
      }
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
  
  public void persistIfNotAlready(T obj) throws IOException {
    if (!obj.hasBeenPersisted()) {
      persist(obj);
    }
  }

  public void persist(T obj) throws IOException {
    final String msg = gson.toJson(obj)+'\n';
   
    File tempFile = File.createTempFile(FIRST_PART, TMP_PART, baseDir);
    try (FileOutputStream writer = new FileOutputStream(tempFile,true)) {
      writer.write(msg.getBytes(StandardCharsets.UTF_8));
    }
    File persistFile = new File(baseDir,tempFile.getName().replaceAll(TMP_PART, LAST_PART));
    boolean done = tempFile.renameTo(persistFile);
    if (done) {
      obj.setPersistFile(persistFile);
    } else {
      IOException e = new IOException(String.format("PR Unable to rename file %s to %s", tempFile, persistFile));
      log.error("Unable to rename file", e);
      throw e;
    }
  }

  final static class PersisterFileFilter implements FileFilter {

    @Override
    public boolean accept(File pathname) {
      String name = pathname.getName();
      try {
        if (name.startsWith(FIRST_PART) && name.endsWith(LAST_PART)) {
          return true;
        }
      } catch (Exception e) {
        log.error("PR File {} is not allowed here, remove it please ",pathname,e);
      }
      return false;
    }
  }
}
