package com.google.gerrit.common.replication;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.FailedEventUtil;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

import static com.google.gerrit.common.replication.ReplicationConstants.ENC;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_INDEX_EVENT;

public class FailedEventUtilTest extends AbstractReplicationTesting {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  public ReplicatedScheduling scheduling;
  public File outgoingDir;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertNotNull(dummyTestCoordinator);
    outgoingDir = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
  }

  @After
  public void tearDown() {

    File outgoingPath = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
    File incomingPath = dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory();

    File[] oEntries = outgoingPath.listFiles();
    File[] iEntries = incomingPath.listFiles();

    assert oEntries != null;
    int oLen = oEntries.length;
    assert iEntries != null;
    int iLen = iEntries.length;

    File[] result = new File[oLen + iLen];

    System.arraycopy(oEntries, 0, result, 0, oLen);
    System.arraycopy(iEntries, 0, result, oLen, iLen);


    for(File currentFile: result){
      currentFile.delete();
    }
  }


  @Test
  public void testRemoveUpTo_FailedEventIsFirstEvent() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    List<EventWrapper> eventWrapperList = Arrays.asList(dummyWrapper2, dummyWrapper3, dummyWrapper4, dummyWrapper5);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 4);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper2.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(1).toString(), dummyWrapper3.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(2).toString(), dummyWrapper4.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(3).toString(), dummyWrapper5.toString());
  }



  @Test
  public void testRemoveUpTo_FailedEventIsSecondEvent() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    List<EventWrapper> eventWrapperList = Arrays.asList(dummyWrapper1, dummyWrapper3, dummyWrapper4, dummyWrapper5);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 4);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper1.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(1).toString(), dummyWrapper3.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(2).toString(), dummyWrapper4.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(3).toString(), dummyWrapper5.toString());
  }


  @Test
  public void testRemoveUpTo_FailedEventIsThirdEvent() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    List<EventWrapper> eventWrapperList = Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper4, dummyWrapper5);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 4);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper1.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(1).toString(), dummyWrapper2.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(2).toString(), dummyWrapper4.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(3).toString(), dummyWrapper5.toString());
  }

  @Test
  public void testRemoveUpTo_FailedEventIsFourthEvent() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    List<EventWrapper> eventWrapperList = Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper3, dummyWrapper5);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 4);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper1.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(1).toString(), dummyWrapper2.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(2).toString(), dummyWrapper3.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(3).toString(), dummyWrapper5.toString());
  }


  @Test
  public void testRemoveUpTo_FailedEventIsLastEvent() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    List<EventWrapper> eventWrapperList = Arrays.asList(dummyWrapper1, dummyWrapper2, dummyWrapper3, dummyWrapper4);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 4);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper1.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(1).toString(), dummyWrapper2.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(2).toString(), dummyWrapper3.toString());
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(3).toString(), dummyWrapper4.toString());
  }


  @Test
  public void testRemoveUpTo_multipleFailedEvents() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    final String projectName = "ProjectA";
    EventWrapper dummyWrapper1 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper2 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper3 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper4 = createIndexEventWrapper(projectName);
    EventWrapper dummyWrapper5 = createIndexEventWrapper(projectName);

    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);
    byte[] bytes4 = getEventBytes(dummyWrapper4);
    byte[] bytes5 = getEventBytes(dummyWrapper5);


    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, StringUtils.createUniqueString("events-test"), projectName);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);
    persistedEventInformation.writeEventsToFile(dummyWrapper4.getProjectName(), bytes4);
    persistedEventInformation.writeEventsToFile(dummyWrapper5.getProjectName(), bytes5);

    long origSize = persistedEventInformation.getEventFile().length();

    //Only 1 event succeeded
    List<EventWrapper> eventWrapperList = Collections.singletonList(dummyWrapper4);

    ReplicatedEventTask replicatedEventTask = new ReplicatedEventTask(projectName,
        persistedEventInformation.getEventFile(), dummyTestCoordinator);

    FailedEventUtil.persistRemainingEvents(dummyTestCoordinator, replicatedEventTask, eventWrapperList);

    long newSize = persistedEventInformation.getEventFile().length();
    Assert.assertTrue(origSize != newSize);

    Assert.assertEquals(countEventLines(persistedEventInformation.getEventFile()), 1);
    Assert.assertEquals(remainingEventsList(persistedEventInformation.getEventFile()).get(0).toString(), dummyWrapper4.toString());
  }



  private int countEventLines(final File eventFile){
    int lines = 0;
    try(BufferedReader reader = new BufferedReader(new FileReader(eventFile))){
      while (reader.readLine() != null) lines++;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return lines;
  }


  private List<EventWrapper> remainingEventsList(final File eventFile){

    List<EventWrapper> eventWrapperList = new ArrayList<>();
    try(BufferedReader reader = new BufferedReader(new FileReader(eventFile))) {

      Gson gson = dummyTestCoordinator.getGson();
      String currentLine;
      while ((currentLine = reader.readLine()) != null) {

        // trim newline when comparing with lineToRemove
        String trimmedCurrentLine = currentLine.trim();
        eventWrapperList.add(gson.fromJson(trimmedCurrentLine, EventWrapper.class));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return eventWrapperList;
  }


  private byte[] getEventBytes(final EventWrapper eventWrapper) throws UnsupportedEncodingException {
    Gson gson = dummyTestCoordinator.getGson();
    final String wrappedEvent = gson.toJson(eventWrapper) + '\n';
    return wrappedEvent.getBytes(ENC);
  }


  private EventWrapper createIndexEventWrapper(String projectName) throws IOException {
    int randomIndexId = new Random().nextInt(1000);
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(randomIndexId, dummyTestCoordinator.getThisNodeIdentity());
    return GerritEventFactory.createReplicatedAccountIndexEvent(
        projectName, accountIndexEvent, ACCOUNT_INDEX_EVENT);
  }

}
