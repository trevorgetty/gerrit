
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

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gwtorm.client.KeyUtil;
import com.google.gwtorm.server.StandardKeyEncoder;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

public class ProjectLoader {

  private final GitRepositoryManager mgr;
  private final ProjectStateMinDepends.Factory projectStateFactory;
  private final Project.NameKey project;

  static {
    KeyUtil.setEncoderImpl(new StandardKeyEncoder());
  }

  public interface Factory {
    ProjectLoader create(Project.NameKey project);
  }

  @Inject
  public ProjectLoader(ProjectStateMinDepends.Factory projectStateFactory, GitRepositoryManager mgr,
                       @Assisted Project.NameKey project) {
    this.mgr = mgr;
    this.projectStateFactory = projectStateFactory;
    this.project = project;
  }

  public String getProjectName() {
    return project.get();
  }

  public ProjectStateMinDepends getProjectSnapshot() throws Exception {
    return getProjectSnapshot(project);
  }

  public Config getProjectConfigSnapshot() throws Exception {
    return getProjectConfigSnapshot(project);
  }

  /**
   * Open Project, get configuration but take a SNAPSHOT of it at this point in time.
   *
   * @param projectName  * String representation of projectname.
   * @return
   * @throws Exception
   */
  public ProjectStateMinDepends getProjectSnapshot(String projectName) throws Exception {

    Project.NameKey key = new Project.NameKey(projectName);
    return getProjectSnapshot(key);
  }

  /**
   * Open Project, get configuration but take a SNAPSHOT of it at this point in time.
   *
   * @param key  Project name key object, representing project name.
   * @return
   */
  public ProjectStateMinDepends getProjectSnapshot(Project.NameKey key) throws Exception {
    try (Repository git = mgr.openRepository(key)) {
      ProjectConfig cfg = new ProjectConfig(key);
      cfg.load(git);

      final ProjectStateMinDepends state = projectStateFactory.create(key);
      state.setConfig(cfg);
      return state;
    }
  }

  /**
   * Open Project, get git configuration but take a SNAPSHOT of it at this point in time.
   *
   * @param key  Project name key object, representing project name.
   * @return Git Configuration
   */
  public Config getProjectConfigSnapshot(Project.NameKey key) throws Exception {
    try (Repository git = mgr.openRepository(key)) {
      return new Config(git.getConfig());
    }
  }

  /**
   * Obtain configuration file or information from a project. This could be any file within
   * any project given.
   * E.g. LFS.config file from the AllProjects repo.
   *
   * @param fileName
   * @param project
   * @return
   * @throws Exception
   */
  public com.google.gerrit.gerritconsoleapi.bindings.ProjectLevelConfigNoCache getConfigFromProject(String fileName, ProjectStateMinDepends project) throws Exception {

    // Get the ProjectLevel information, without having to clone the project ( via tree walk ).
    ProjectLevelConfigNoCache cfg = new ProjectLevelConfigNoCache(fileName, project);
    Project.NameKey name = project.getProject().getNameKey();
    try (Repository git = mgr.openRepository(name)) {
      cfg.load(name, git);
    } catch (ConfigInvalidException e) {
      throw new Exception(String.format("Failed to load %s for %s", fileName, name.toString()), e);
    }
    
    return cfg;
  }
}

