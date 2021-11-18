package com.google.gerrit.common.replication;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ReplicatedThreadPoolExecutor extends ThreadPoolExecutor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;

  // handy metrics, basically we record start time and when we terminate either in a good way or bad, we log out
  // the duration.  It uses the debug / fine output to stop polluting the logs, but can be turned on if required.
  private final ThreadLocal startTime
      = new ThreadLocal();

  // allow the executor access to our WIP list.
  public ReplicatedThreadPoolExecutor(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    super(replicatedEventsCoordinator.getReplicatedConfiguration().getCoreProjects().size(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads(),
        replicatedEventsCoordinator.getReplicatedConfiguration().getMaxIdlePeriodEventWorkerThreadInSeconds(),
        TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
        new ReplicatedThreadFactory(ReplicatedThreadPoolExecutor.class.getSimpleName()));

    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
  }

  protected void beforeExecute(Thread t, Runnable r) {
    super.beforeExecute(t, r);
    logger.atFine().log("Thread %s: start %s", t.getName(), r.toString());
    startTime.set(System.nanoTime());
  }

  protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);

    long endTime = System.nanoTime();
    long taskTime = endTime - (long) startTime.get();

    if (t == null && r instanceof Future<?>) {
      // deal with future executions from scheduled pools - which we also allow to be overridden,
      // but if they throw uncaught exception please don't let it kill our gerrit process.
      try {
        ((Future<?>) r).get(); // We don't use result of this - just want to know if it throws.
      } catch (CancellationException ce) {
        t = ce; // just log this as error - who cancelled this future??
      } catch (ExecutionException ee) {
        t = ee.getCause(); // just log this as error - but hide the reason - don't throw it will kill gerrit process.
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt(); // ignore/reset this needs to happen to not block on shutdown.
      }
    }

    // Remove the wip for this runnable / ReplicatedEventTask.
    if (r instanceof ReplicatedEventTask && replicatedEventsCoordinator.isGerritIndexerRunning()) {
      if (taskTime > 0) {
        // log out the output time for this event processor, and the num events if possible.
        logger.atFine().log("RE Worker Thread finished task: %s, with endTime %s, taskTime=%dns", r, endTime, taskTime);
      }

      // Belts and braces protection here.
      // - JIC something went wrong and an exception has happened somewhere between us scheduling the work in progress
      // and the Run on the ReplicatedEventTask being called ( and not executed ).
      // This would leave the WIP list being stale for this project as nothing would delete the event therefore
      // blocking the pool.
      // Log if this ever happens - its just a belt and braces and I hope it never does.
      replicatedEventsCoordinator.getReplicatedScheduling().clearEventsFileInProgress((ReplicatedEventTask) r, true);
    }

    if (t != null) {
      // Some kind of throwable has happened - lets hide execution exceptions
      logger.atWarning().withCause(t).log("Experienced Exception from a ReplicatedThreadPool item, note shutdown cancellations can be safely ignored. ");
    }

  }

  protected void terminated() {
    try {
      // we could do work here around time, that would cover success and failure timings, but for now leave as is.
    } finally {
      super.terminated();
    }
  }

}
