
/********************************************************************************
 * Copyright (c) 2014-2020 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/

package com.google.gerrit.sshd.commands;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.replication.Replicator;
import com.google.gerrit.common.Version;

import static com.google.gerrit.server.permissions.GlobalPermission.VIEW_REPLICATOR_STATS;
import static com.google.gerrit.sshd.CommandMetaData.Mode.MASTER_OR_SLAVE;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.IdentifiedUser;

import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;

import com.google.inject.Inject;
import org.apache.sshd.server.Environment;

import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Show the current Wandisco Replicator Statistics
 */
@RequiresCapability(GlobalCapability.VIEW_REPLICATOR_STATS)
@CommandMetaData(name = "show-replicator-stats", description = "Display statistics from the WD replicator",
    runsAt = MASTER_OR_SLAVE)
final class ShowReplicatorStats extends SshCommand {
  private static volatile long serverStarted;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  private IdentifiedUser currentUser;

  @Inject
  private PermissionBackend permissionBackend;

  static class StartupListener implements LifecycleListener {
    @Override
    public void start() {
      serverStarted = TimeUtil.nowMs();
    }

    @Override
    public void stop() {
    }
  }

  @Override
  public void start(final Environment env) throws IOException {
    super.start(env);
  }

  @Override
  protected void run() throws Failure {

    try {
      permissionBackend.user(currentUser).check(VIEW_REPLICATOR_STATS);
    } catch (@SuppressWarnings("UnusedException") AuthException | PermissionBackendException ex) {
      String msg = String.format("fatal: %s does not have \"View Replicator Stats\" capability.",
          currentUser.getUserName());
      logger.atSevere().withCause(ex).log(msg);
      throw new UnloggedFailure(msg);
    }

    Date now = new Date();
    stdout.format(
        "%-25s %-20s      now  %16s\n",
        "Gerrit Code Review",
        Version.getVersion() != null ? Version.getVersion() : "",
        new SimpleDateFormat("HH:mm:ss   zzz").format(now));
    stdout.format(
        "%-25s %-20s          uptime %16s\n",
        "", "",
        uptime(now.getTime() - serverStarted));
    stdout.print('\n');

    Replicator repl = Replicator.getInstance();

    stdout.print("---------------------------------------------------------------------------+\n");
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Statistic", "Sent", "Received"));
    stdout.print("---------------------------------------------------------------------------+\n");

    ImmutableMultiset<Originator> totalPublishedForeignEventsByType = repl.getTotalPublishedForeignEventsByType();
    ImmutableMultiset<Originator> totalPublishedLocalEventsByType = repl.getTotalPublishedLocalEventsByType();

    for (Originator orig : Originator.values()) {
      stdout.print(String.format("%-30s | %19s | %19s |\n", //
          orig + " messages:",
          totalPublishedLocalEventsByType.count(orig),
          totalPublishedForeignEventsByType.count(orig)));
    }
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total published events:",
        repl.getTotalPublishedLocalEvents(),
        repl.getTotalPublishedForeignEvents()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "      of which with errors:",
        repl.getTotalPublishedLocalEvents() - repl.getTotalPublishedLocalGoodEvents(),
        repl.getTotalPublishedForeignEvents() - repl.getTotalPublishedForeignGoodEvents()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total bytes published:",
        repl.getTotalPublishedLocalEventsBytes(),
        repl.getTotalPublishedForeignEventsBytes()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total MiB published:",
        (repl.getTotalPublishedLocalEventsBytes() * 10 / (1024 * 1024)) / 10.0,
        (repl.getTotalPublishedForeignEventsBytes() * 10 / (1024 * 1024)) / 10.0));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total gzipped MiB published:",
        (repl.getTotalPublishedLocalEventsBytes() * 6 / 100 / (1024 * 1024) * 10) / 10.0,
        (repl.getTotalPublishedForeignEventsBytes() * 6 / 100 / (1024 * 1024) * 10) / 10.0));

    long localProposals = repl.getTotalPublishedLocalEventsProsals();
    long foreignProposals = repl.getTotalPublishedForeignEventsProsals();

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total proposals published:",
        localProposals,
        foreignProposals));

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg Events/proposal:",
        localProposals == 0 ? "n/a" : (repl.getTotalPublishedLocalEvents() * 10 / localProposals) / 10.0,
        foreignProposals == 0 ? "n/a" : (repl.getTotalPublishedForeignEvents() * 10 / foreignProposals) / 10.0));

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg bytes/proposal:",
        localProposals == 0 ? "n/a" : repl.getTotalPublishedLocalEventsBytes() / localProposals,
        foreignProposals == 0 ? "n/a" : repl.getTotalPublishedForeignEventsBytes() / foreignProposals));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg gzipped bytes/proposal:",
        localProposals == 0 ? "n/a" : repl.getTotalPublishedLocalEventsBytes() * 6 / 100 / localProposals,
        foreignProposals == 0 ? "n/a" : repl.getTotalPublishedForeignEventsBytes() * 6 / 100 / foreignProposals));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //ErrorLog
        "Files in Incoming directory:", "n/a", repl.getIncomingDirFileCount()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Files in Outgoing directory:", "n/a", repl.getOutgoingDirFileCount()));

    stdout.println();
  }

  // Copied from ShowCaches.java to print the uptime
  private String uptime(long uptimeMillis) {
    if (uptimeMillis < 1000) {
      return String.format("%3d ms", uptimeMillis);
    }

    long uptime = uptimeMillis / 1000L;

    long min = uptime / 60;
    if (min < 60) {
      return String.format("%2d min %2d sec", min, uptime - min * 60);
    }

    long hr = uptime / 3600;
    if (hr < 24) {
      min = (uptime - hr * 3600) / 60;
      return String.format("%2d hrs %2d min", hr, min);
    }

    long days = uptime / (24 * 3600);
    hr = (uptime - (days * 24 * 3600)) / 3600;
    return String.format("%4d days %2d hrs", days, hr);
  }
}
