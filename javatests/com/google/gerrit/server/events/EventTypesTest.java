// Copyright (C) 2015 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Sets;
import org.eclipse.jgit.util.StringUtils;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EventTypesTest {
  public static class TestEvent extends Event {
    private static final String TYPE = "test-event";

    public TestEvent() {
      super(TYPE);
    }
  }

  public static class AnotherTestEvent extends Event {
    private static final String TYPE = "another-test-event";

    public AnotherTestEvent() {
      super("another-test-event");
    }
  }

  @SkipReplication
  public static class SkipTestEvent extends Event {
    private static final String TYPE = "skip-test-event";

    public SkipTestEvent() {
      super(TYPE);
    }
  }


  @Test
  public void eventTypeRegistration() {
    try {
      EventTypes.register(TestEvent.TYPE, TestEvent.class);
      EventTypes.register(AnotherTestEvent.TYPE, AnotherTestEvent.class);
      assertThat(EventTypes.getClass(TestEvent.TYPE)).isEqualTo(TestEvent.class);
      assertThat(EventTypes.getClass(AnotherTestEvent.TYPE)).isEqualTo(AnotherTestEvent.class);
    }finally {
      EventTypes.unregister(TestEvent.TYPE);
      EventTypes.unregister(AnotherTestEvent.TYPE);
    }
  }

  @Test
  public void getClassForNonExistingType() {
    Class<?> clazz = EventTypes.getClass("does-not-exist-event");
    assertThat(clazz).isNull();
  }


  @Test
  public void testKnownChangeEvents() throws NoSuchFieldException, IllegalAccessException {
    //These events are present in gerrit 2.13 and gerrit 2.16
    List<Class<? extends Event>> knownEventClasses = new ArrayList<>(Arrays.asList(
        ChangeAbandonedEvent.class, ChangeMergedEvent.class, ChangeRestoredEvent.class, CommentAddedEvent.class,
        CommitReceivedEvent.class, HashtagsChangedEvent.class, PatchSetCreatedEvent.class,
        ProjectCreatedEvent.class, RefReceivedEvent.class,
        RefUpdatedEvent.class, ReviewerAddedEvent.class, ReviewerDeletedEvent.class,
        TopicChangedEvent.class));

    //new events as of gerrit 2.16
    List<Class<? extends Event>> knownGerrit216EventClasses = new ArrayList<>(Arrays.asList(
        VoteDeletedEvent.class, WorkInProgressStateChangedEvent.class,
        PrivateStateChangedEvent.class, AssigneeChangedEvent.class, ChangeDeletedEvent.class));
    knownEventClasses.addAll(knownGerrit216EventClasses);

    //Getting the events we actually support and compare them.
    //When the EventTypes class loads, there is a static initializer that
    //registers the events. It is these registered events that we want to compare against.
    EventTypes eventTypes = new EventTypes();
    Class clazz = eventTypes.getClass();
    Field field = clazz.getDeclaredField("typesByString");
    field.setAccessible(true);
    Map<String, String> refMap = (HashMap<String, String>) field.get(eventTypes);
    Class[] clsArray = refMap.values().toArray(new Class[0]);

    Set<Class> foundSet = new HashSet<Class>(Arrays.asList(clsArray));
    Set<Class> knownSet = new HashSet<Class>(knownEventClasses);

    //Comparing the two sets of classes. If one set contains or is missing something
    //from the other set then we should fail.
    String msg = "";
    if (!knownSet.containsAll(foundSet)) {
      msg = String.format("New events have been found : %s", Sets.difference(foundSet, knownSet));
    }

    if (!foundSet.containsAll(knownSet)) {
      msg = String.format("Old events have been removed: %s", Sets.difference(knownSet, foundSet));
    }

    if(!StringUtils.isEmptyOrNull(msg)){
      throw new AssertionError(msg);
    }
  }

  //This test is to check that we can verify the presence of the skipReplication
  //annotation on a given event class. SkipTestEvent has been annotated with the
  // @SkipReplication annotation.
  @Test
  public void testSkipReplicationAnnotationPresent() {
    try {
      EventTypes.register(SkipTestEvent.TYPE, SkipTestEvent.class);
      Class eventClass = EventTypes.getClass(SkipTestEvent.TYPE);
      if (!eventClass.isAnnotationPresent(SkipReplication.class)) {
        throw new AssertionError("SkipReplication annotation not found");
      }
    }finally {
      EventTypes.unregister(SkipTestEvent.TYPE);
    }
  }

}


