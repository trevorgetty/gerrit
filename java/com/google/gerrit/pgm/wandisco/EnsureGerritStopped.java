package com.google.gerrit.pgm.wandisco;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Die;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.wandisco.gerrit.gitms.shared.util.ProcessRunner;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.net.ConnectException;

public class EnsureGerritStopped implements UninstallStep {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final String GERRIT_STATUS_COMMAND = "status";

    private static final String GERRIT_RUNNING_WARNING = "Gerrit is still running. Please shutdown any running " +
                                                         "instances before continuing!";
    private final SitePaths site;

    @Inject
    public EnsureGerritStopped(SitePaths site) {
        this.site = site;
    }

    @Override
    public void run() throws Die {
        checkWebUrl(getGerritCanonicalWebUrl());
        checkPid();
    }

    private void checkWebUrl(String canonicalWebUrl) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            final HttpGet request = new HttpGet(canonicalWebUrl);
            logger.atInfo().log("Checking Gerrit canonicalWebUrl - %s", canonicalWebUrl);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                // if we receive a response, Gerrit is running
                throw new Die(GERRIT_RUNNING_WARNING);
            }
        } catch (IOException e) {
            if (e instanceof ConnectException) {
                // Couldn't open connection - Gerrit may not be running
                return;
            }
            throw new Die("Failed to check if Gerrit is still running over HTTP(S).", e);
        }
     }

    private void checkPid() {
        logger.atInfo().log("Checking Gerrit PID via script - %s %s",
                            site.gerrit_sh.toFile().getAbsolutePath(),
                            GERRIT_STATUS_COMMAND);
        try {
            ProcessRunner<String> pr = new ProcessRunner<>(site.gerrit_sh.toFile().getAbsolutePath(),
                                                           GERRIT_STATUS_COMMAND);
            pr.setResultBehaviourAsSb(true);

            try {
                pr.startProcess();
            } catch(Exception e) {
                // ignore throw and examine return code
            }
            final Integer returnCode = pr.getExitValue().orElse(null);

            // gerrit.sh will return 0 if running. If we can't determine the return code, play it safe and die.
            if (returnCode == null || Integer.valueOf(0).equals(returnCode)) {
                throw new Die(GERRIT_RUNNING_WARNING);
            }
        } catch (Exception e) {
            throw new Die(String.format("Could not execute process - %s %s",
                                        site.gerrit_sh.toFile().getAbsolutePath(),
                                        GERRIT_STATUS_COMMAND), e);
        }
    }

    private String getGerritCanonicalWebUrl() {
        FileBasedConfig cfg = new FileBasedConfig(site.gerrit_config.toFile(), FS.DETECTED);
        if (!cfg.getFile().exists()) {
            throw new Die("gerrit.config does not exist at this site location. Check --site-path parameter is correct");
        }

        try {
            cfg.load();
            return cfg.getString("gerrit", null, "canonicalWebUrl");
        } catch (IOException | ConfigInvalidException e) {
            throw new Die("Could not access `canonicalWebUrl` in gerrit.config", e);
        }
    }
}
