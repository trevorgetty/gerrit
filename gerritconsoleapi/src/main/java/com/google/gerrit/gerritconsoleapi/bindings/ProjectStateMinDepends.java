
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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.*;
import com.google.gerrit.extensions.api.projects.ThemeInfo;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.BranchOrderSection;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.project.*;
import com.google.inject.Inject;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


import static java.nio.charset.StandardCharsets.UTF_8;

public class ProjectStateMinDepends {
  private static final Logger log =
      LoggerFactory.getLogger(ProjectStateMinDepends.class);

  public interface Factory {
    ProjectStateMinDepends create(ProjectConfig config);
  }

  private boolean isAllProjects;
  private final SitePaths sitePaths;
  private final AllProjectsName allProjectsName;
  private final GitRepositoryManager gitMgr;
  private ProjectConfig config;
  /**
   * Local access sections, wrapped in SectionMatchers for faster evaluation.
   */
  private volatile List<SectionMatcher> localAccessSections;

  /**
   * Theme information loaded from site_path/themes.
   */
  private volatile ThemeInfo theme;

  @Inject
  public ProjectStateMinDepends(
      final SitePaths sitePaths,
      final AllProjectsName allProjectsName,
      final GitRepositoryManager gitMgr) {
    this.sitePaths = sitePaths;
    this.allProjectsName = allProjectsName;
    this.gitMgr = gitMgr;

  }

  public void setConfig(ProjectConfig config){
    this.config = config;
    if ( config == null ) {
      throw new NullPointerException("No configuration given to ProjectStateMinDepends");
    }
    this.isAllProjects = config.getProject().getNameKey().equals(allProjectsName);
  }

  public Project getProject() {
    return config.getProject();
  }

  public ProjectConfig getConfig() {
    return config;
  }

  public ProjectLevelConfigNoCache getConfig(String fileName) {


    ProjectLevelConfigNoCache cfg = new ProjectLevelConfigNoCache(fileName, this);
    try (Repository git = gitMgr.openRepository(getProject().getNameKey())) {
      cfg.load(git);
    } catch (IOException | ConfigInvalidException e) {
      log.warn("Failed to load " + fileName + " for " + getProject().getName(), e);
    }

    return cfg;
  }

  public long getMaxObjectSizeLimit() {
    return config.getMaxObjectSizeLimit();
  }

  /**
   * Get the sections that pertain only to this project.
   */
  List<SectionMatcher> getLocalAccessSections() {
    List<SectionMatcher> sm = localAccessSections;
    if (sm == null) {
      Collection<AccessSection> fromConfig = config.getAccessSections();
      sm = new ArrayList<>(fromConfig.size());
      for (AccessSection section : fromConfig) {
        if (isAllProjects) {
          List<Permission> copy =
              Lists.newArrayListWithCapacity(section.getPermissions().size());
          for (Permission p : section.getPermissions()) {
            if (Permission.canBeOnAllProjects(section.getName(), p.getName())) {
              copy.add(p);
            }
          }
          section = new AccessSection(section.getName());
          section.setPermissions(copy);
        }

        SectionMatcher matcher = SectionMatcher.wrap(getProject().getNameKey(),
            section);
        if (matcher != null) {
          sm.add(matcher);
        }
      }
      localAccessSections = sm;
    }
    return sm;
  }

  /**
   * Obtain all local and inherited sections. This collection is looked up
   * dynamically and is not cached. Callers should try to cache this result
   * per-request as much as possible.
   */
  List<SectionMatcher> getAllSections() {
    if (isAllProjects) {
      return getLocalAccessSections();
    }

    List<SectionMatcher> all = new ArrayList<>();
    for (ProjectStateMinDepends s : tree()) {
      all.addAll(s.getLocalAccessSections());
    }
    return all;
  }


  /**
   * @return an iterable that walks through the parents of this project. Starts
   * from the immediate parent of this project and progresses up the
   * hierarchy to All-Projects.
   */
  public Iterable<ProjectStateMinDepends> parents() {
    return null;
  }

  public boolean isAllProjects() {
    return isAllProjects;
  }

  public LabelTypes getLabelTypes() {
    Map<String, LabelType> types = new LinkedHashMap<>();
    for (ProjectStateMinDepends s : treeInOrder()) {
      for (LabelType type : s.getConfig().getLabelSections().values()) {
        String lower = type.getName().toLowerCase();
        LabelType old = types.get(lower);
        if (old == null || old.canOverride()) {
          types.put(lower, type);
        }
      }
    }
    List<LabelType> all = Lists.newArrayListWithCapacity(types.size());
    for (LabelType type : types.values()) {
      if (!type.getValues().isEmpty()) {
        all.add(type);
      }
    }
    return new LabelTypes(Collections.unmodifiableList(all));
  }

  public BranchOrderSection getBranchOrderSection() {
    for (ProjectStateMinDepends s : tree()) {
      BranchOrderSection section = s.getConfig().getBranchOrderSection();
      if (section != null) {
        return section;
      }
    }
    return null;
  }


  /**
   * @return an iterable that walks through this project and then the parents of
   * this project. Starts from this project and progresses up the
   * hierarchy to All-Projects.
   */
  public Iterable<ProjectStateMinDepends> tree() {
    // we are only using All-Projects for now, so it has no parents, put back in if we ever need to support inheritance/parent walking.
    return new ArrayList<ProjectStateMinDepends>();
  }

  /**
   * @return an iterable that walks in-order from All-Projects through the
   *     project hierarchy to this project.
   */
  public Iterable<ProjectStateMinDepends> treeInOrder() {
    List<ProjectStateMinDepends> projects = Lists.newArrayList(tree());
    Collections.reverse(projects);
    return projects;
  }


  public Collection<SubscribeSection> getSubscribeSections(
      Branch.NameKey branch) {
    Collection<SubscribeSection> ret = new ArrayList<>();
    for (ProjectStateMinDepends s : tree()) {
      ret.addAll(s.getConfig().getSubscribeSections(branch));
    }
    return ret;
  }

  public ThemeInfo getTheme() {
    ThemeInfo theme = this.theme;
    if (theme == null) {
      synchronized (this) {
        theme = this.theme;
        if (theme == null) {
          theme = loadTheme();
          this.theme = theme;
        }
      }
    }
    if (theme == ThemeInfo.INHERIT) {
      ProjectStateMinDepends parent = Iterables.getFirst(parents(), null);
      return parent != null ? parent.getTheme() : null;
    }
    return theme;
  }

  private ThemeInfo loadTheme() {
    String name = getConfig().getProject().getName();
    Path dir = sitePaths.themes_dir.resolve(name);
    if (!Files.exists(dir)) {
      return ThemeInfo.INHERIT;
    } else if (!Files.isDirectory(dir)) {
      log.warn("Bad theme for {}: not a directory", name);
      return ThemeInfo.INHERIT;
    }
    try {
      return new ThemeInfo(readFile(dir.resolve(SitePaths.CSS_FILENAME)),
          readFile(dir.resolve(SitePaths.HEADER_FILENAME)),
          readFile(dir.resolve(SitePaths.FOOTER_FILENAME)));
    } catch (IOException e) {
      log.error("Error reading theme for " + name, e);
      return ThemeInfo.INHERIT;
    }
  }

  private String readFile(Path p) throws IOException {
    return Files.exists(p) ? new String(Files.readAllBytes(p), UTF_8) : null;
  }

}
