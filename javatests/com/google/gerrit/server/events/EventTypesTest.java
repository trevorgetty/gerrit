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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Strings;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EventTypesTest {

  private static final String KNOWN_EVENTS_FILE="known_event_types.txt";

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
  public void testKnownChangeEvents() throws Exception {
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
    List<String> eventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);
    List<String> knownEventTypes = getListFromResource(KNOWN_EVENTS_FILE);

    List<String> KnownIn213 = convertList(knownEventClasses, Class::toString);
    List<String> knownAddedIn216 = convertList(knownGerrit216EventClasses, Class::toString);

    List<String> combinedKnown = Stream.of(KnownIn213, knownAddedIn216)
        .flatMap(Collection::stream).collect(Collectors.toList());

    //Need to make sure that the events file contains the hard coded
    //events here. This is just a precautionary step in case the known
    //events file is not kept up to date.
    assertTrue(knownEventTypes.containsAll(combinedKnown));

    checkEventSetDifferences(eventTypesInCurrentVersion, knownEventTypes);
  }


  //Finds and returns a list of all event types.
  private List<Class> getAllEventClasses() throws NoSuchFieldException, IllegalAccessException {
    EventTypes eventTypes = new EventTypes();
    Class clazz = eventTypes.getClass();
    Field field = clazz.getDeclaredField("typesByString");
    field.setAccessible(true);
    Map<String, String> refMap = (HashMap<String, String>) field.get(eventTypes);
    return Arrays.asList(refMap.values().toArray(new Class[0]));
  }


  // Transforms each object type in the stream using the map function
  // to convert one type to another.
  public static <T, U> List<U> convertList(List<T> from, Function<T, U> func) {
    return from.stream().map(func).collect(Collectors.toList());
  }

  @Test(expected = AssertionError.class)
  public void testNewEventTypeAdded() throws Exception {
    //Converting event types of Type Class to String here.
    List<String> eventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);

    eventTypesInCurrentVersion.add("class com.google.gerrit.server.events.BrandNewEvent");

    List<String> knownEventTypes = getListFromResource(KNOWN_EVENTS_FILE);

    checkEventSetDifferences(eventTypesInCurrentVersion, knownEventTypes);
  }

  @Test(expected = AssertionError.class)
  public void testEventTypeRemoved() throws Exception {
    //Converting event types of Type Class to String here.
    List<String> eventTypesInCurrentVersion = convertList(getAllEventClasses(), Class::toString);

    List<String> knownEventTypes = getListFromResource(KNOWN_EVENTS_FILE);
    knownEventTypes.remove("class com.google.gerrit.server.events.ChangeDeletedEvent");

    checkEventSetDifferences(eventTypesInCurrentVersion, knownEventTypes);
  }


  // Compares two sets, events in the current version vs events in our known_events_types.txt file
  // Throws an AssertionError if any differences found with the relevant message.
  private void checkEventSetDifferences(List<String> eventTypesInCurrentVersion, List<String> knownEventTypes) {
    Set<String> oldFilterNew = knownEventTypes.stream()
        .distinct()
        .filter(val -> !eventTypesInCurrentVersion.contains(val))
        .collect(Collectors.toSet());

    Set<String> newFilterOld = eventTypesInCurrentVersion.stream()
        .distinct()
        .filter(val -> !knownEventTypes.contains(val))
        .collect(Collectors.toSet());

    StringBuilder errMsg = new StringBuilder();

    if(!oldFilterNew.isEmpty()) {
      errMsg.append(String.format("\nNew event types have been found " +
          "that are not in our current known set [ %s ], have new events been added? ", oldFilterNew));
    }

    if(!newFilterOld.isEmpty()) {
      errMsg.append(String.format("\nEvent types exist in the known set " +
          "that are not present in this version [ %s ], have they been removed? ", newFilterOld));
    }

    if(!Strings.isNullOrEmpty(errMsg.toString())){
      throw new AssertionError(errMsg);
    }
  }

  //The known_event_types text file in resources / events is read as an
  // inputStream and each line added to a list and returned.
  private List<String> getListFromResource(String resource) throws Exception {
    List<String> eventsList = new ArrayList<>();
    try (InputStream in = this.getClass().getResourceAsStream(resource)) {
      if (in == null) {
        throw new Exception(String.format("%s list not found", resource));
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF_8));
      String line;
      while ((line = r.readLine()) != null) {
        eventsList.add(line);
      }
    }
    return eventsList;
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


