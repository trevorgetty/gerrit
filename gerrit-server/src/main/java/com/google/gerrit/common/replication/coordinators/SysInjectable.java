package com.google.gerrit.common.replication.coordinators;

import com.google.inject.Injector;

public interface SysInjectable {
  Injector getSysInjector();

  void setSysInjector(Injector sysInjector);
}
