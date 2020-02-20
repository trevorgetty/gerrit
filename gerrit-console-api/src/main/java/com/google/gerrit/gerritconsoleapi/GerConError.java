package com.google.gerrit.gerritconsoleapi;

public enum GerConError {

  LFS_CONFIG_INFO_ERROR(1,"LFS Config"),
  LFS_CONTENT_ERROR(2, "LFS Content"),
  LFS_RUNTIME_ERROR(3,"LFS Runtime"),
  LFS_STORAGE_BACKEND_ERROR(4, "LFS Backend"),
  GENERAL_OBJECT_CREATION_ERROR(5, "General Object Creation"),
  GENERAL_RUNTIME_ERROR(254, "General Runtime Error"),
  DEFAULT_ERROR(255, "Default Exception");

  private final int code;
  private final String description;

  private GerConError(int code, String description) {
    this.code = code;
    this.description = description;
  }

  public String getDescription() {
    return description;
  }

  public int getCode() {
    return code;
  }

  @Override
  public String toString() {
    return code + ": " + description;
  }
}

