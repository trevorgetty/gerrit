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

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

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

  @Test
  public void testEventTypeRegistration() {
    EventTypes.register(TestEvent.TYPE, TestEvent.class);
    EventTypes.register(AnotherTestEvent.TYPE, AnotherTestEvent.class);
    assertThat(EventTypes.getClass(TestEvent.TYPE)).isEqualTo(TestEvent.class);
    assertThat(EventTypes.getClass(AnotherTestEvent.TYPE))
      .isEqualTo(AnotherTestEvent.class);
  }

  @Test
  public void testGetClassForNonExistingType() {
    Class<?> clazz = EventTypes.getClass("does-not-exist-event");
    assertThat(clazz).isNull();
  }

  @Test
  public void testAllChangeEventsReplicated() throws NoSuchFieldException, IllegalAccessException,
      NoSuchMethodException {

    //Getting the events we actually support and compare them
    EventTypes eventTypes = new EventTypes();
    Class clazz = eventTypes.getClass();
    Field field = clazz.getDeclaredField("typesByString");
    field.setAccessible(true);
    Map<String, String> refMap = (HashMap<String, String>) field.get(eventTypes);
    Class [] clsArray = refMap.values().toArray(new Class[0]);

    for(int i = 0; i < clsArray.length; i++){
      //Check the class is a subclass of ChangeEvent
      if(ChangeEvent.class.isAssignableFrom(clsArray[i])) {
        //Ensure that the class has a constructor of the following format that contains
        // a boolean parameter as this will be for our replicated flag. If the event type
        //does not have a constructor that has a boolean it is indicative of a replicated event that
        //has been missed for replication.

        //If the event is decorated with the SkipReplication annotation
        //then we can ignore the event from the check.
        if(clsArray[i].isAnnotationPresent(SkipReplication.class)){
          continue;
        }

        //NOTE: reflection does not support getting parameter names for constructors
        //as they are not available in the bytecode.
        try{
          clsArray[i].getDeclaredConstructor(clsArray[i], String.class, boolean.class);
        } catch (NoSuchMethodException e){
          String failStr = String.format("No matching constructor found. "
              + "%s doesn't have a constructor with a \"replicated\" boolean parameter which defines"
              + " if the event type is a WANdisco replicated event or not. ", clsArray[i]);
          throw new AssertionError(failStr);
        }
      }
    }
  }
}
