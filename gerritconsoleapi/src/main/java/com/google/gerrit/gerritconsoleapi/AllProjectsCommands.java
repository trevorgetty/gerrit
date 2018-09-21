
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
 
package com.google.gerrit.gerritconsoleapi;


import com.google.gerrit.gerritconsoleapi.bindings.ProjectLevelConfigNoCache;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectLoader;

import com.google.gerrit.gerritconsoleapi.bindings.ProjectStateMinDepends;
import com.google.inject.Injector;

import java.util.Set;


public class AllProjectsCommands {


  private final ProjectLoader projectLoader;
  private final String configName;

  public AllProjectsCommands( final Injector programContext, final String configName )
  {

    if ( programContext == null ){
      throw new NullPointerException("Null guice context supplied is invalid.");
    }

    projectLoader = programContext.getInstance(ProjectLoader.class);
    this.configName = configName;
  }


  /**
   * Returns the name of the config within the All-Projects repo to parse
   *
   * @return
   */
  public String getConfigName() {
    return configName;
  }

  /**
   * Will display the entire contents of the specified config file
   */
  public void displayConfig() {
    String config = getProjectConfig().get().toText();
    System.out.println(config);
  }

  /**
   * display all the config sections
   */
  public void displaySections() {
    Set<String> sections = getProjectConfig().get().getSections();
    final String configVal = getString(sections);
    System.out.println(configVal);
  }

  /**
   * display the subsections for a given section
   */
  public void displaySubSectionsForSection(String sectionName) {

    Set<String> subSections = getProjectConfig().get().getSubsections(sectionName);
    final String configVal = getString(subSections);
    System.out.println(configVal);
  }

  private String getString(final Set<String> subSections) {
    StringBuilder sb = new StringBuilder();

    for ( String subSect : subSections ) {
      if ( sb.length() > 0 ){
        sb.append(System.lineSeparator());
      }
      sb.append(subSect);
    }

    return sb.toString();
  }

  /**§
   * Returns the specified configuration file from the allProjects repo.
   * @return
   */
  public ProjectLevelConfigNoCache getProjectConfig() {
    try {

      ProjectLevelConfigNoCache projectLevelConfig = projectLoader.getConfigFromProject(getConfigName(), getAllProjects());
      if (projectLevelConfig == null) {
        throw new NullPointerException(String.format("No project configuration was found for configuration: %s", getConfigName()));
      }
      return projectLevelConfig;
    } catch (Exception e) {
      // throw Runtime nothing else we can do.  
      throw new RuntimeException(e);
    }
  }

  /**
   * Utility method to return the AllProjects node from the projectLoader.
   * @return
   * @throws Exception
   */
  private ProjectStateMinDepends getAllProjects() throws Exception {

    ProjectStateMinDepends state = projectLoader.getAllProjects();
    return state;
  }
}
