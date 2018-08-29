package com.google.gerrit.gerritconsoleapi;

import com.google.common.collect.Iterables;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.git.ProjectLevelConfig;
import com.google.gerrit.server.git.VersionedMetaData;
import com.google.gerrit.server.project.ProjectState;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;

import java.io.IOException;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


public class ProjectLevelConfigNoCache extends VersionedMetaData {
    private final String fileName;
    private final ProjectStateMinDepends project;
    private Config cfg;

  public ProjectLevelConfigNoCache(String fileName, ProjectStateMinDepends projectConfig) {
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
