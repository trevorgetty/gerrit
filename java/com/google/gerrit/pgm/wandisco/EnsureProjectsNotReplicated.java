package com.google.gerrit.pgm.wandisco;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Die;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectLoader;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Check if Gerrit projects All-Projects & All-Users are still in replication.
 * If these are still in replication. Terminate the process.
 *
 * This class reuses dependencies from gerrit-console-api module.
 *
 * @author conal
 */
public class EnsureProjectsNotReplicated implements UninstallStep {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final String PROJECT_NOT_LOADED_MESSAGE = "Project %s could not be loaded.";

    private final ProjectLoader allProjectsLoader;

    private final ProjectLoader allUsersLoader;

    @Inject
    public EnsureProjectsNotReplicated(ProjectLoader.Factory factory,
                                       AllProjectsName allProjectsName,
                                       AllUsersName allUsersName) {
        this.allProjectsLoader = factory.create(allProjectsName);
        this.allUsersLoader = factory.create(allUsersName);
    }

    @Override
    public void run() throws Die {
        logger.atInfo().log("Checking %s & %s config to verify they have been removed from replication.",
                            allProjectsLoader.getProjectName(), allUsersLoader.getProjectName());

        boolean allProjectsReplicated = isReplicated(getProjectConfig(allProjectsLoader));
        boolean allUsersReplicated = isReplicated(getProjectConfig(allUsersLoader));

        if (allProjectsReplicated || allUsersReplicated) {
            List<String> projectsStillReplicating = new ArrayList<>();
            if (allProjectsReplicated) {
                projectsStillReplicating.add(allProjectsLoader.getProjectName());
            }
            if(allUsersReplicated) {
                projectsStillReplicating.add(allUsersLoader.getProjectName());
            }
            final String msg = String.format("Projects still have replicated=true in their git config. " +
                                       "Please ensure %s %s been removed from replication via GitMS prior to " +
                                       "running the UninstallWD command.",
                                       projectsStillReplicating,
                                       projectsStillReplicating.size() > 1 ? "have" : "has");
            throw new Die(msg);
        }
    }

    /**
     * Load project configuration from provided project loader.
     *
     * @param projectLoader project loader used to retrieve config
     * @return project configuration
     */
    private Config getProjectConfig(ProjectLoader projectLoader) {
        try {
            final Config config = projectLoader.getProjectConfigSnapshot();

            if (config == null) {
                throw new Die(String.format(PROJECT_NOT_LOADED_MESSAGE, projectLoader.getProjectName()));
            }

            return config;
        } catch (Exception e) {
            throw new Die(String.format(PROJECT_NOT_LOADED_MESSAGE, projectLoader.getProjectName()), e);
        }
    }

    private boolean isReplicated(Config config) {
        // default to true for safety
        return config.getBoolean("core", "replicated", true);
    }
}
