package com.google.gerrit.common.replication;

import java.util.concurrent.ThreadFactory;

/**
 * New threads are created using a ThreadFactory. If not otherwise specified,
 * a Executors.defaultThreadFactory() is used, that creates threads to all be in
 * the same ThreadGroup and with the same NORM_PRIORITY priority and non-daemon status.
 * By supplying a different ThreadFactory, you can alter the thread's name, thread group, priority,
 * daemon status, etc. If a ThreadFactory fails to create a thread when asked by returning null
 * from newThread, the executor will continue, but might not be able to execute any tasks
 */
public class ReplicatedThreadFactory implements ThreadFactory {
  private String threadName;
  public ReplicatedThreadFactory(final String threadName) {
    this.threadName = threadName;
  }
  @Override
  public Thread newThread(Runnable r) {
    return new Thread(r, this.threadName);
  }
}
