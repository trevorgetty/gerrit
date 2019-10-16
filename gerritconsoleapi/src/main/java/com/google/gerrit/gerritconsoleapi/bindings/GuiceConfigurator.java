
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

import com.google.gerrit.common.Die;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectLoader;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectStateMinDepends;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManagerModule;
import com.google.gerrit.server.schema.*;

import com.google.gerrit.server.securestore.DefaultSecureStore;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.inject.*;

import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.inject.Stage.PRODUCTION;

public class GuiceConfigurator {

  private Injector cfgInjector;
  private Injector mainInjector;
  private Path sitePath;
  private Config config;

  public GuiceConfigurator(Path sitePath) {
    this.sitePath = sitePath;

    mustHaveValidSite();

    mainInjector = this.createMainInjector();
  }

  public Injector getMainInjector() {
    return mainInjector;
  }

  /**
   * Ensures we are running inside of a valid site, otherwise throws a Die.
   */
  protected void mustHaveValidSite() throws Die {
    if (!Files.exists(sitePath.resolve("etc").resolve("gerrit.config"))) {
      throw die("not a Gerrit site: '" + getSitePath() + "'\n"
          + "Perhaps you need to run init first?");
    }
  }

  /**
   * The site path of the Gerrit installation
   *
   * @return
   */
  protected Path getSitePath() {
    return sitePath;
  }

  /**
   * Get the secure.store.class property based on the sitePath.
   *
   * @param sitePath
   * @return
   */
  public static String getSecureStoreClassName(final Path sitePath) {
    if (sitePath != null) {
      return getSecureStoreFromGerritConfig(sitePath);
    }

    String secureStoreProperty = System.getProperty("gerrit.secure_store_class");
    return nullToDefault(secureStoreProperty);
  }

  /**
   * Utility method using ternary operator which returns the className or a
   * default secure store className.
   *
   * @param className
   * @return
   */
  private static String nullToDefault(String className) {
    return className != null ? className : DefaultSecureStore.class.getName();
  }

  /**
   * Calls getSecureStoreClassName with a sitePath
   *
   * @return
   */
  protected final String getConfiguredSecureStoreClass() {
    return getSecureStoreClassName(sitePath);
  }

  /**
   * The secureStoreClass can be worked out based on the sitePath. The
   * gerrit.config is loaded to get the secureStoreClass.
   *
   * @param sitePath
   * @return
   */
  private static String getSecureStoreFromGerritConfig(final Path sitePath) {
    AbstractModule m = new AbstractModule() {
      @Override
      protected void configure() {
        bind(Path.class).annotatedWith(SitePath.class).toInstance(sitePath);
        bind(SitePaths.class);
      }
    };
    Injector injector = Guice.createInjector(m);
    SitePaths site = injector.getInstance(SitePaths.class);
    FileBasedConfig cfg = new FileBasedConfig(site.gerrit_config.toFile(), FS.DETECTED);
    if (!cfg.getFile().exists()) {
      return DefaultSecureStore.class.getName();
    }

    try {
      cfg.load();
      String className = cfg.getString("gerrit", null, "secureStoreClass");
      return nullToDefault(className);
    } catch (IOException | ConfigInvalidException e) {
      throw new ProvisionException(e.getMessage(), e);
    }
  }

  /**
   *
   * @return
   */
  protected Injector createMainInjector() {
    final Path sitePath = getSitePath();
    final List<Module> modules = new ArrayList<>();

    // Configuration modules so we can add modules based on configuration present.
    Module sitePathModule = new AbstractModule() {
      @Override
      protected void configure() {
        bind(Path.class).annotatedWith(SitePath.class).toInstance(sitePath);
        bind(String.class).annotatedWith(SecureStoreClassName.class)
            .toProvider(Providers.of(getConfiguredSecureStoreClass()));
      }
    };
    modules.add(sitePathModule);
    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        bind(MetricMaker.class).to(DisabledMetricMaker.class);
      }
    });
    Module configModule = new GerritServerConfigModule();
    modules.add(configModule);

    // Create a standalone context, that can obtain simple gerrit configuration from the sitePath.
    cfgInjector = Guice.createInjector(configModule, sitePathModule);

    // Get hold of the config instance from our new cfginjector
    config = cfgInjector.getInstance(
        Key.get(Config.class, GerritServerConfig.class));

    // Now the rest of the modules are to startup this application with gitRepository management, no cache or
    // migration logic.
    modules.add(new SchemaModule());
    modules.add(cfgInjector.getInstance(GitRepositoryManagerModule.class));
    modules.add(new DataSourceModule());

    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
// Bind my own impl of ProjectLoader which avoids the use of the ProjectCache.
        bind(ProjectLoader.class);
        bind(ProjectStateMinDepends.class);
      }
    });


    try {
      return Guice.createInjector(PRODUCTION, modules);
    } catch (CreationException ce) {
      final Message first = ce.getErrorMessages().iterator().next();
      Throwable why = first.getCause();

      throw die("Problem creating guice injector:", why);
    }
  }

  /**
   * General kill method.
   *
   * @param why
   * @param cause
   * @return
   */
  protected static Die die(String why, Throwable cause) {
    return new Die(why, cause);
  }

  /**
   * General kill method.
   *
   * @param why
   * @return
   */
  protected static Die die(String why) {
    return new Die(why);
  }
}
