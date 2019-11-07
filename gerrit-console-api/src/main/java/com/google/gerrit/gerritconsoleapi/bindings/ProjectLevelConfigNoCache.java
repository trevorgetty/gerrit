
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
 
package com.google.gerrit.gerritconsoleapi.bindings;

import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.git.meta.VersionedMetaData;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;

import java.io.IOException;


public class ProjectLevelConfigNoCache extends VersionedMetaData {
    private final String fileName;
    private final com.google.gerrit.gerritconsoleapi.bindings.ProjectStateMinDepends project;
    private Config cfg;

  public ProjectLevelConfigNoCache(String fileName, com.google.gerrit.gerritconsoleapi.bindings.ProjectStateMinDepends projectConfig) {
    this.fileName = fileName;
    this.project = projectConfig;
  }

    @Override
    protected String getRefName() {
      return RefNames.REFS_CONFIG;
    }

    @Override
    protected void onLoad() throws IOException, ConfigInvalidException {
      cfg = readConfig(fileName);
    }

    public Config get() {
      if (cfg == null) {
        cfg = new Config();
      }
      return cfg;
    }

    // get configuration without inheritance, i.e. either cfg directly in this project e.g.  All-Projects.
    // we do NOT walk the parent tree inheriting other configuration.
    public Config getTopLevelCfg() {

      Config rootCfg = new Config();
      try {
        rootCfg.fromText(get().toText());
      } catch (ConfigInvalidException e) {
        // cannot happen
      }

      return rootCfg;
    }

    @Override
    protected boolean onSave(CommitBuilder commit) throws IOException,
        ConfigInvalidException {
      if (commit.getMessage() == null || "".equals(commit.getMessage())) {
        commit.setMessage("Updated configuration\n");
      }
      saveConfig(fileName, cfg);
      return true;
    }
  }
