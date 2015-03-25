// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.reviewdb.client;

import com.google.gwtorm.client.Column;
import com.google.gwtorm.client.IntKey;

import java.sql.Timestamp;

public final class SlaveWait {
  public static class Id extends IntKey<com.google.gwtorm.client.Key<?>> {
    private static final long serialVersionUID = 1L;

    @Column(id = 1)
    protected int id;

    protected Id() {
    }

    public Id(final int id) {
      this.id = id;
    }

    @Override
    public int get() {
      return id;
    }

    @Override
    protected void set(int newValue) {
      id = newValue;
    }

    /** Parse a Change.Id out of a string representation. */
    public static Id parse(final String str) {
      final Id r = new Id();
      r.fromString(str);
      return r;
    }
  }

  /** Locally assigned unique identifier of the change */
  @Column(id = 1)
  protected Id waitId;

  /** When this change was first introduced into the database. */
  @Column(id = 2)
  protected Timestamp createdOn;

  /** Current state code; see Status */
  @Column(id = 3)
  protected char status;

  protected SlaveWait() {
  }

  public SlaveWait(SlaveWait.Id newId, Timestamp ts) {
    waitId = newId;
    createdOn = ts;
    //setStatus(Status.NEW);
  }

  public SlaveWait(SlaveWait other) {
    waitId = other.waitId;
    createdOn = other.createdOn;
    status = other.status;
  }

  /** Legacy 32 bit integer identity for a change. */
  public SlaveWait.Id getId() {
    return waitId;
  }

  /** Legacy 32 bit integer identity for a change. */
  public int getWaitId() {
    return waitId.get();
  }

  public Timestamp getCreatedOn() {
    return createdOn;
  }

//  public Status getStatus() {
//    return Status.forCode(status);
//  }

//  public void setStatus(final Status newStatus) {
//    open = newStatus.isOpen();
//    status = newStatus.getCode();
//  }

}
