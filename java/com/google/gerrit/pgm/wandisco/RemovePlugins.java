package com.google.gerrit.pgm.wandisco;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Die;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;

import java.util.HashSet;
import java.util.Set;

/**
 * Inform the user they need to remove WANdisco replicated plugins from their Gerrit
 * installation.
 *
 * TODO use plugin loader to dynamically check for presence of replicated plugins
 */
public class RemovePlugins implements UninstallStep {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final Set<String> pluginsToRemove;

    private final ConsoleUI ui;

    private final SitePaths site;

    static {
        pluginsToRemove = new HashSet<>();
        pluginsToRemove.add("delete-project");
        pluginsToRemove.add("lfs");
    }

    @Inject
    public RemovePlugins(ConsoleUI ui, SitePaths site) {
        this.ui = ui;
        this.site = site;
    }

    @Override
    public void run() throws Die {
        logger.atInfo().log("Please remove WANdisco replicated plugins %s from %s", pluginsToRemove,
                            site.plugins_dir.toAbsolutePath().toString());
        ui.message("Please remove WANdisco replicated plugins %s from %s\n\n", pluginsToRemove,
                   site.plugins_dir.toAbsolutePath().toString());
    }
}
