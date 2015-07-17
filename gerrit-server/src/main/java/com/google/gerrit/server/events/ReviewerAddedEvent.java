// Copyright (C) 2012 The Android Open Source Project
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
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;

public class ReviewerAddedEvent extends ChangeEvent {
    public final String type;
    public ChangeAttribute change;
    public PatchSetAttribute patchSet;
    public AccountAttribute reviewer;

    public ReviewerAddedEvent(){
      this.type = "reviewer-added";
    }

    public ReviewerAddedEvent(ReviewerAddedEvent e, String type){
      this(e, type, false);
    }

    public ReviewerAddedEvent(ReviewerAddedEvent e, String type, boolean replicated){
      this.type = type;
      this.change = e.change;
      this.patchSet = e.patchSet;
      this.reviewer = e.reviewer;
      this.replicated = replicated;
    }

    @Override
    public String getType(){
      return type;
    }

}
