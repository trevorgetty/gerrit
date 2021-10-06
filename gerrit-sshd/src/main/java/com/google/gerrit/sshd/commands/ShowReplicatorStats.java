
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
 
package com.google.gerrit.sshd.commands;

import com.google.common.collect.ImmutableMultiset;
import com.google.gerrit.common.Version;
import static com.google.gerrit.sshd.CommandMetaData.Mode.MASTER_OR_SLAVE;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.ReplicatorMetrics;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;

import com.google.inject.Inject;

import com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator;

import org.apache.sshd.server.Environment;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;


/** Show the current WANdisco Replicator Statistics */
@RequiresCapability(GlobalCapability.VIEW_REPLICATOR_STATS)
@CommandMetaData(name = "show-replicator-stats", description = "Display statistics from the WD replicator",
  runsAt = MASTER_OR_SLAVE)
final class ShowReplicatorStats extends SshCommand {
  private static volatile long serverStarted;

  @Inject
  private IdentifiedUser currentUser;
  @Inject
  private ReplicatedConfiguration replicatedConfiguration;

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
    if (!currentUser.getCapabilities().canViewReplicatorStats()) {
      String msg = String.format("fatal: %s does not have \"View Replicator Stats\" capability.",
          currentUser.getUserName());
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

    stdout.print("---------------------------------------------------------------------------+\n");
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Statistic", "Sent", "Received"));
    stdout.print("---------------------------------------------------------------------------+\n");

    ImmutableMultiset<Originator> totalPublishedForeignEventsByType = ReplicatorMetrics.getTotalPublishedForeignEventsByType();
    ImmutableMultiset<Originator> totalPublishedLocalEventsByType = ReplicatorMetrics.getTotalPublishedLocalEventsByType();

    for(Originator orig: Originator.values()) {
      stdout.print(String.format("%-30s | %19s | %19s |\n", //
          orig+" messages:",
          totalPublishedLocalEventsByType.count(orig),
          totalPublishedForeignEventsByType.count(orig)));
    }
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total published events:",
        ReplicatorMetrics.getTotalPublishedLocalEvents(),
        ReplicatorMetrics. getTotalPublishedForeignEvents()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "      of which with errors:",
        ReplicatorMetrics.getTotalPublishedLocalEvents()-ReplicatorMetrics.getTotalPublishedLocalGoodEvents(),
        ReplicatorMetrics.getTotalPublishedForeignEvents()-ReplicatorMetrics.getTotalPublishedForeignGoodEvents()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total bytes published:",
        ReplicatorMetrics.getTotalPublishedLocalEventsBytes(),
        ReplicatorMetrics.getTotalPublishedForeignEventsBytes()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total MiB published:",
        (ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*10/(1024*1024))/10.0,
        (ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*10/(1024*1024))/10.0));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total gzipped MiB published:",
        (ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*6/100/(1024*1024)*10)/10.0,
        (ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*6/100/(1024*1024)*10)/10.0));

    long localProposals = ReplicatorMetrics.getTotalPublishedLocalEventsProposals();
    long foreignProposals = ReplicatorMetrics.getTotalPublishedForeignEventsProsals();

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Total proposals published:",
        localProposals,
        foreignProposals));

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg Events/proposal:",
        localProposals == 0 ? "n/a": (ReplicatorMetrics.getTotalPublishedLocalEvents()*10/localProposals)/10.0,
        foreignProposals == 0 ? "n/a": (ReplicatorMetrics.getTotalPublishedForeignEvents()*10/foreignProposals)/10.0));

    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg bytes/proposal:",
        localProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedLocalEventsBytes()/localProposals,
        foreignProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedForeignEventsBytes()/foreignProposals));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Avg gzipped bytes/proposal:",
        localProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedLocalEventsBytes()*6/100/localProposals,
        foreignProposals == 0 ? "n/a": ReplicatorMetrics.getTotalPublishedForeignEventsBytes()*6/100/foreignProposals));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Files in Incoming directory:", "n/a",getIncomingDirFileCount()));
    stdout.print(String.format("%-30s | %19s | %19s |\n", //
        "Files in Outgoing directory:", "n/a",getOutgoingDirFileCount()));

    stdout.println();
  }

  public int getIncomingDirFileCount() {
    int result = -1;
    if (replicatedConfiguration.getIncomingFailedReplEventsDirectory() != null) {
      long now = System.currentTimeMillis();
      if (now - ReplicatorMetrics.lastCheckedIncomingDirTime > ReplicatorMetrics.DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for lastCheckedIncomingDirTime ms, so that
        // continuous requests do not disturb
        File[] listFilesResult =
            replicatedConfiguration.getIncomingFailedReplEventsDirectory().listFiles();

        if (listFilesResult != null) {
          ReplicatorMetrics.lastIncomingDirValue = listFilesResult.length;

          result = ReplicatorMetrics.lastIncomingDirValue;
        }
        ReplicatorMetrics.lastCheckedIncomingDirTime = now;
      }
    }
    return result;
  }

  public int getOutgoingDirFileCount() {
    int result = -1;
    if (replicatedConfiguration.getOutgoingReplEventsDirectory() != null) {
      long now = System.currentTimeMillis();
      if (now - ReplicatorMetrics.lastCheckedOutgoingDirTime > ReplicatorMetrics.DEFAULT_STATS_UPDATE_TIME) {
        // we cache the last result for lastCheckedOutgoingDirTime ms, so that
        // continuous requests do not disturb
        File[] listFilesResult =
            replicatedConfiguration.getOutgoingReplEventsDirectory().listFiles();

        if (listFilesResult != null) {
          ReplicatorMetrics.lastOutgoingDirValue = listFilesResult.length;

          result = ReplicatorMetrics.lastOutgoingDirValue;
        }
        ReplicatorMetrics.lastCheckedOutgoingDirTime = now;
      }
    }
    return result;
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
