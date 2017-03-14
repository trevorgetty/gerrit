package com.google.gerrit.common;

import java.io.File;

/**
 *
 * @author antonio
 */
public interface Persistable {
  File getPersistFile();
  void setPersistFile(File file);
  boolean hasBeenPersisted();
}
