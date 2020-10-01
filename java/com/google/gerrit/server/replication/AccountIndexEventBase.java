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

package com.google.gerrit.server.replication;

import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

public abstract class AccountIndexEventBase extends ReplicatedEvent implements AccountIndexIdentification {

  public AccountIndexEventBase(String nodeIdentity) {
    super(nodeIdentity);
  }
}
