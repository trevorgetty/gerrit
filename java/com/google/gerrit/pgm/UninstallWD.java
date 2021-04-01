package com.google.gerrit.pgm;

import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.util.ErrorLogFile;
import com.google.gerrit.pgm.util.SiteProgram;
import com.google.gerrit.pgm.wandisco.AcknowledgeCorrectState;
import com.google.gerrit.pgm.wandisco.EnsureGerritStopped;
import com.google.gerrit.pgm.wandisco.EnsureProjectsNotReplicated;
import com.google.gerrit.pgm.wandisco.RemovePlugins;
import com.google.gerrit.pgm.wandisco.RevertRepoSequence;
import com.google.gerrit.pgm.wandisco.UninstallModule;
import com.google.gerrit.pgm.wandisco.UninstallStep;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Providers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.gerrit.server.schema.DataSourceProvider.Context.SINGLE_USER;

/**
 * PGM command to uninstall a GerritMS instance, reverting it back to vanilla functionality.
 *
 * @author conal
 */
public class UninstallWD extends SiteProgram {

    private static final String CMD_NOTICE = "+================================================================+\n"
                                           + "|                 Uninstalling WANdisco GerritMS                 |\n"
                                           + "|                                                                |\n"
                                           + "|  You are now reverting GerritMS back to a vanilla Gerrit       |\n"
                                           + "|  installation.                                                 |\n"
                                           + "|                                                                |\n"
                                           + "|                                                                |\n"
                                           + "|  Please ensure you are running at a site with ALL repositories |\n"
                                           + "|  present and removed from replication & that GitMS has been    |\n"
                                           + "|  shut down.                                                    |\n"
                                           + "+================================================================+\n"
                                             + "\n\n";

    private static final String UNINSTALL_SUCCESS =
            "+================================================================+\n" +
            "|               Uninstall of GerritMS is now complete            |\n" +
            "+================================================================+\n\n";

    private static final String NEXT_STEPS = "-> Next steps:\n";

    private static final List<String> STEPS;

    static {
        STEPS = new ArrayList<>();
        STEPS.add("\t - Uninstall replicated Git binaries and replace with vanilla;\n");
        STEPS.add("\t - Uninstall GitMS;\n");
        STEPS.add("\t - Reinstall vanilla Gerrit to continue.\n\n");

    }

    private final LifecycleManager manager = new LifecycleManager();

    private final ConsoleUI ui = ConsoleUI.getInstance(false);

    @Override
    public int run() throws Exception {
        mustHaveValidSite();

        ui.message(CMD_NOTICE);

        Injector inj = init();
        final List<UninstallStep> uninstallSteps = new ArrayList<>();

        uninstallSteps.add(inj.getInstance(AcknowledgeCorrectState.class));
        uninstallSteps.add(inj.getInstance(EnsureProjectsNotReplicated.class));
        uninstallSteps.add(inj.getInstance(RevertRepoSequence.class));
        uninstallSteps.add(inj.getInstance(RemovePlugins.class));

        uninstallSteps.forEach(UninstallStep::run);

        ui.message(UNINSTALL_SUCCESS);

        ui.message(NEXT_STEPS);
        STEPS.forEach(step -> ui.message(step));

        return 0;
    }

    private Injector init() {
        List<Module> modules = new ArrayList<>();
        Module sitePathModule =
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(Path.class).annotatedWith(SitePath.class).toInstance(getSitePath());
                        bind(String.class)
                                .annotatedWith(SecureStoreClassName.class)
                                .toProvider(Providers.of(getConfiguredSecureStoreClass()));
                    }
                };
        modules.add(sitePathModule);
        Module configModule = new GerritServerConfigModule();
        modules.add(configModule);

        AbstractModule gerritRunningModule = new AbstractModule() {
            @Override
            public void configure() {
                bind(ConsoleUI.class).toInstance(ui);
                bind(EnsureGerritStopped.class);
            }
        };
        modules.add(gerritRunningModule);

        Injector cgrInjector = Guice.createInjector(modules);
        EnsureGerritStopped cgr = cgrInjector.getInstance(EnsureGerritStopped.class);

        ErrorLogFile.errorOnlyConsole();
        // initial check for running gerrit in trimmed down guice context so process doesn't clash on db locks if
        // Gerrit is running
        cgr.run();

        Injector dbInjector = createDbInjector(SINGLE_USER);
        manager.add(dbInjector);

        manager.start();

        return dbInjector.createChildInjector(new UninstallModule());
    }

}
