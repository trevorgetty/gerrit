/********************************************************************************
 * Copyright (c) 2014-2019 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
package com.google.gerrit.common;

import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;


/********************************************************************************
 * This lock manager contains a hashmap of reentrant locks.  A lock is obtained
 * by taking the modulus of an id, locking the lock and then returning it.
 *
 ********************************************************************************/
public class BatchUpdateLockManager {

  private static final int NUMBER_OF_LOCKS = 4096;

  private static final HashMap<Integer, ReentrantLock> LOCKMAP = new HashMap<>();

  private BatchUpdateLockManager() {
    for (int i = 0; i < NUMBER_OF_LOCKS; i++) {
      ReentrantLock currentLock = new ReentrantLock();
      LOCKMAP.put(new Integer(i), currentLock);
    }
  }

  private static class LazyLockManagerLoader {
    public static final BatchUpdateLockManager INSTANCE = new BatchUpdateLockManager();
}

  public static BatchUpdateLockManager getInstance() {
      return LazyLockManagerLoader.INSTANCE;
  }

  public ReentrantLock obtainLock(int id) {
    ReentrantLock changeLock = LOCKMAP.get(id % NUMBER_OF_LOCKS);
    changeLock.lock();
    return changeLock;
  }

  public void releaseLock(int id) {
    ReentrantLock changeLock = LOCKMAP.get(id % NUMBER_OF_LOCKS);
    changeLock.unlock();
  }
}
