
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
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public class ProjectLoader {

  private final static Logger logger = LoggerFactory.getLogger(ProjectLoader.class);

  private final GitRepositoryManager mgr;
  private final Injector injector;
  private final AllProjectsName allProjects;

  @Inject
  public ProjectLoader(Injector injector, GitRepositoryManager mgr, AllProjectsName allProjects) {

    this.mgr = mgr;
    this.injector = injector;
    this.allProjects = allProjects;
  }

  public ProjectStateMinDepends getAllProjects() throws Exception {
    return getProjectSnapshot(allProjects);
  }

  /** Open Project, get configuration but take a SNAPSHOT of it at this point in time.
   *
   * @param projectName  * String representation of projectname.
   * @return
   * @throws Exception
   */
  public ProjectStateMinDepends getProjectSnapshot(String projectName) throws Exception {

    Project.NameKey key = new Project.NameKey(projectName);
    return getProjectSnapshot(key);
  }

  /** Open Project, get configuration but take a SNAPSHOT of it at this point in time.
   *
   * @param key  Project name key object, representing project name.
   * @return
   */
  public ProjectStateMinDepends getProjectSnapshot(Project.NameKey key) throws Exception {
    try (Repository git = mgr.openRepository(key)) {
      ProjectConfig cfg = new ProjectConfig(key);
      cfg.load(git);


      ProjectStateMinDepends state = injector.getInstance(ProjectStateMinDepends.class);
      state.setConfig(cfg);
      return state;
    }
  }

  /**
   * Obtain configuration file or information from a project.   This could be any file within
   * any project given.
   * E.g. LFS.config file from the AllProjects repo.
   *
   * @param fileName
   * @param project
   * @return
   * @throws Exception
   */
  public ProjectLevelConfigNoCache getConfigFromProject(String fileName, ProjectStateMinDepends project) throws Exception {

    // Get the ProjectLevel information, without have to clone the project ( via tree walk ).
    ProjectLevelConfigNoCache cfg = new ProjectLevelConfigNoCache(fileName, project);
    try (Repository git = mgr.openRepository(project.getProject().getNameKey())) {
      cfg.load(git);
    } catch (ConfigInvalidException e) {
      logger.warn("Failed to load {} for {}", fileName, project.getProject().getName(), e);
      throw new Exception("Failed to load " + fileName + " for " + project.getProject().getName(), e);
    }


    return cfg;
  }
}

