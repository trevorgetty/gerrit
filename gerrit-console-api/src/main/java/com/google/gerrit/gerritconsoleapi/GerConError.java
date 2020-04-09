package com.google.gerrit.gerritconsoleapi;

public enum GerConError {

  COMMAND_SUCCESSFUL(0, "Command successful"),
  DEFAULT_ERROR(1, "Default Exception"),
  GENERAL_RUNTIME_ERROR(2, "General Runtime Error"),
  LFS_CONFIG_INFO_ERROR(3,"LFS Config"),
  LFS_CONTENT_ERROR(4, "LFS Content"),
  LFS_RUNTIME_ERROR(5, "LFS Runtime"),
  LFS_STORAGE_BACKEND_ERROR(6, "LFS Backend"),
  GENERAL_OBJECT_CREATION_ERROR(7, "General Object Creation");
  
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

