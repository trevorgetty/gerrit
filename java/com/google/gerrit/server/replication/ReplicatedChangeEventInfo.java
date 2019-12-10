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

package com.google.gerrit.server.replication;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.server.data.ChangeAttribute;

/**
 * Structure to represent the change event information which is transmitted when replicating Change Events.
 *
 */
public class ReplicatedChangeEventInfo {

  private ChangeAttribute changeAttr = null;
  private Branch.NameKey branchName = null;
  private String projectName = null;
  private boolean supported = false;

  public void setChangeAttribute(ChangeAttribute changeAttr) {
    this.changeAttr = changeAttr;
    if (changeAttr != null) {
      projectName = changeAttr.project;
      supported = true;
    }
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
    this.supported = true;
  }

  public void setBranchName(Branch.NameKey branchName) {
    this.branchName = branchName;
    supported = true;
  }

  public void setSupported(boolean supported) {
    this.supported = supported;
  }

  public ChangeAttribute getChangeAttr() {
    return changeAttr;
  }

  public Branch.NameKey getBranchName() {
    return branchName;
  }

  public String getProjectName() {
    return projectName;
  }

  public boolean isSupported() {
    return supported;
  }

  @Override
  public String toString() {
    return String.format("ReplicatedChangeEventInfo{projectName=%s, branchName=%s, changeAttr=%s, supported=%s}",
        projectName, branchName, changeAttr, supported);

  }
}

