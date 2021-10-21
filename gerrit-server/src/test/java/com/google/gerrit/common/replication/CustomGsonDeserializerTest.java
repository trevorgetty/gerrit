package com.google.gerrit.common.replication;

import com.google.gerrit.common.FailedEventUtil;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static com.google.gerrit.common.replication.ReplicationConstants.ENC;

public class CustomGsonDeserializerTest extends AbstractReplicationTesting {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  public ReplicatedScheduling scheduling;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties testingProperties = new Properties();
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertNotNull(dummyTestCoordinator);
  }


  @Test
  public void testUsingBasicGsonDeserializer_TypesInPropertiesMapIncorrect() throws IOException, InvalidEventJsonException {
    final String projectName = "ProjectA";
    final Gson basicGson = new Gson();

    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper1);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);

    List<EventWrapper> sortedEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EVentWrapper and then adds to the sortedEvents list
      sortedEvents = checkAndSortEvents(bos.toByteArray(), basicGson);
    }

    //Check that after sorting events we have the correct format
    for(EventWrapper ev : sortedEvents){
      Assert.assertFalse(ev.getEventData().get("indexNumber") instanceof Long);
      Assert.assertFalse(ev.getEventData().get("timeZoneRawOffset") instanceof Long);
    }

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, sortedEvents);


    //Check after atomically writing the events to the original file during persistance we still have the correct
    //format
    String [] postWriteEvent;
    List<EventWrapper> afterAtomicRenameEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EventWrapper and then adds to the sortedEvents list
      postWriteEvent = new String(bos.toByteArray(), StandardCharsets.UTF_8).split("\n");

      afterAtomicRenameEvents = getEvents(postWriteEvent, basicGson);
    }

    for(EventWrapper ev : afterAtomicRenameEvents){
      Assert.assertFalse(ev.getEventData().get("indexNumber") instanceof Long);
      Assert.assertFalse(ev.getEventData().get("timeZoneRawOffset") instanceof Long);
    }

  }


  @Test
  public void testUsingCustomGsonDeserializer_TypesInPropertiesMapCorrect() throws IOException, InvalidEventJsonException {
    final String projectName = "ProjectA";
    final Gson providedGson = dummyTestCoordinator.getGson();
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper1);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);

    List<EventWrapper> sortedEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EVentWrapper and then adds to the sortedEvents list
      sortedEvents = checkAndSortEvents(bos.toByteArray(), providedGson);
    }

    //Check that after sorting events we have the correct format
    for(EventWrapper ev : sortedEvents){
      Assert.assertTrue(ev.getEventData().get("indexNumber") instanceof Long);
      Assert.assertTrue(ev.getEventData().get("timeZoneRawOffset") instanceof Long);
    }

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, sortedEvents);


    //Check after atomically writing the events to the original file during persistance we still have the correct
    //format
    String [] postWriteEvent;
    List<EventWrapper> afterAtomicRenameEvents;
    try (ByteArrayOutputStream bos = readFileToByteArrayOutputStream(persistedEventInformation.getEventFile(),
        dummyTestCoordinator.getReplicatedConfiguration().isIncomingEventsAreGZipped())) {
      //Check and sort events deserializes to EventWrapper and then adds to the sortedEvents list
      postWriteEvent = new String(bos.toByteArray(), StandardCharsets.UTF_8).split("\n");

      afterAtomicRenameEvents = getEvents(postWriteEvent, providedGson);
    }

    for(EventWrapper ev : afterAtomicRenameEvents){
      Assert.assertTrue(ev.getEventData().get("indexNumber") instanceof Long);
      Assert.assertTrue(ev.getEventData().get("timeZoneRawOffset") instanceof Long);
    }

  }


  private List<EventWrapper> getEvents(String[] events, final Gson gson) throws InvalidEventJsonException {
    List<EventWrapper> eventDataList = new ArrayList<>();

    for (String event : events) {

      if (event == null) {
        throw new InvalidEventJsonException(
            "Event file is invalid, missing / null events.");
      }

      EventWrapper originalEvent;
      try {
        originalEvent = gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e) {
        throw new InvalidEventJsonException(
            String.format("Event file contains Invalid JSON. \"%s\", \"%s\"",
                event, e.getMessage()));
      }

      if (checkValidEventWrapperJson(originalEvent)) {
        eventDataList.add(originalEvent);
      }
    }
    return eventDataList;
  }


  private List<EventWrapper> checkAndSortEvents(byte[] eventsBytes, final Gson gson)
      throws InvalidEventJsonException {

    List<EventWrapper> eventDataList;
    String[] events =
        new String(eventsBytes, StandardCharsets.UTF_8).split("\n");

    eventDataList = getEvents(events, gson);

    //sort the event data list using a chained comparator.
    Collections.sort(eventDataList,
        new ChainedEventComparator(
            new EventTimestampComparator(),
            new EventNanoTimeComparator()));

    return eventDataList;
  }


  public static ByteArrayOutputStream readFileToByteArrayOutputStream(File file, boolean incomingEventsAreGZipped) throws IOException {

    // ByteArrayOutputStream is an implementation of OutputStream that can write data into a byte array.
    // The buffer keeps growing as ByteArrayOutputStream writes data to it.
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         FileInputStream plainFileReader = new FileInputStream(file);
         // If the incoming events are Gzipped, then the reader will be a GZipInputStream otherwise
         // it will be a FileInputStream.
         InputStream reader = incomingEventsAreGZipped ? new GZIPInputStream(plainFileReader) : plainFileReader) {

      copyFile(reader, bos);
      return bos;
    }

  }


  private static void copyFile(InputStream source, OutputStream dest)
      throws IOException {
    try (InputStream fis = source) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }

  private boolean checkValidEventWrapperJson(EventWrapper originalEvent) throws InvalidEventJsonException {
    if (originalEvent == null) {
      throw new InvalidEventJsonException("Internal error: event is null after deserialization");
    }
    // If the JSON is invalid we will not have been able to get eventTimestamp or eventNanoTime information
    // from it required for sorting, so all we can do is throw an exception here. If the JSON is empty this case
    // will cover {} or ""
    if (originalEvent.getEvent().length() <= 2) {
      throw new InvalidEventJsonException("Internal error, event JSON is invalid ");
    }

    return true;
  }

  private byte[] getEventBytes(final EventWrapper eventWrapper) throws UnsupportedEncodingException {
    Gson gson = dummyTestCoordinator.getGson();
    final String wrappedEvent = gson.toJson(eventWrapper) + '\n';
    return wrappedEvent.getBytes(ENC);
  }


  private EventWrapper createIndexEventWrapper(String projectName) throws IOException {
    int randomIndexId = new Random().nextInt(1000);
    IndexToReplicate indexToReplicate = new IndexToReplicate(randomIndexId, projectName,
        new Timestamp(System.currentTimeMillis()), dummyTestCoordinator.getThisNodeIdentity());

    return GerritEventFactory.createReplicatedIndexEvent(indexToReplicate);
  }



}
