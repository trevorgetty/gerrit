package com.google.gerrit.common.replication;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.server.data.ChangeAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicatedChangeEventInfo {
  private static final Logger log = LoggerFactory.getLogger(ReplicatedChangeEventInfo.class);
  private ChangeAttribute changeAttr = null;
  private Branch.NameKey branchName = null;
  private String projectName = null;

  public void setChangeAttribute(ChangeAttribute changeAttr) {
    if(changeAttr == null){
      log.error("Cannot set ChangeAttribute. ChangeAttribute was null");
      return;
    }
    this.changeAttr = changeAttr;
    this.projectName = changeAttr.project;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public void setBranchName(Branch.NameKey branchName) {
    this.branchName = branchName;
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

}
