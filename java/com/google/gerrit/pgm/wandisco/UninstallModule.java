package com.google.gerrit.pgm.wandisco;

import com.google.gerrit.gerritconsoleapi.bindings.ProjectLoader;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectStateMinDepends;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;

public class UninstallModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(AcknowledgeCorrectState.class);
        bind(EnsureProjectsNotReplicated.class);
        bind(RemovePlugins.class);
        bind(RevertRepoSequence.class);
        install(new FactoryModuleBuilder()
                        .implement(ProjectLoader.class, ProjectLoader.class)
                        .build(ProjectLoader.Factory.class));
        install(new FactoryModuleBuilder()
                        .implement(ProjectStateMinDepends.class, ProjectStateMinDepends.class)
                        .build(ProjectStateMinDepends.Factory.class));
    }

    @Provides
    public ConsoleUI consoleUI() {
        return ConsoleUI.getInstance(false);
    }
}
