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

import com.google.gerrit.server.data.AccountAttribute;

public class HashtagsChangedEvent extends ChangeEvent {
  public AccountAttribute editor;
  public String[] added;
  public String[] removed;
  public String[] hashtags;

  public HashtagsChangedEvent () {
    super("hashtags-changed");
  }

  public HashtagsChangedEvent(HashtagsChangedEvent e, String type){
    this(e, type, false);
  }

  public HashtagsChangedEvent(HashtagsChangedEvent e, String type, boolean replicated){
    super(e, type, replicated);
    this.editor = e.editor;
    this.added = e.added;
    this.removed = e.removed;
    this.hashtags = e.hashtags;
  }
}