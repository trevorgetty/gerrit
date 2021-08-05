
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
 
// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.events;

import com.google.gerrit.server.replication.Replicator;
import com.wandisco.gerrit.gitms.shared.events.ReplicatedEvent;

import java.util.Objects;

public abstract class Event extends ReplicatedEvent {
  public final String type;

  /**
   * WANdisco replication for Gerrit with GitMS
   * This flag is used to make sure that a replicated event
   * does not become a new event to be replicated again, producing
   * this way an infinite loop
   */
  public transient boolean hasBeenReplicated = false;

  protected Event(String type) {
    this.type = type;
    if(!Replicator.isReplicationDisabled()) {
      setNodeIdentity(Objects.requireNonNull(Replicator.getInstance()).getThisNodeIdentity());
    }
  }

  public String getType() {
    return type;
  }
}
