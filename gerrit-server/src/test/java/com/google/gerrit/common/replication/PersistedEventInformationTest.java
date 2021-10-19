package com.google.gerrit.common.replication;

import com.google.common.base.Supplier;
import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

import static com.google.gerrit.common.replication.ReplicationConstants.ENC;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.common.replication.ReplicationConstants.NEXT_EVENTS_FILE;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_INDEX_EVENT;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

public class PersistedEventInformationTest extends AbstractReplicationTesting {

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
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    Assert.assertNotNull(dummyTestCoordinator);
    outgoingDir = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
  }

  @After
  public void tearDown() {

    File outgoingPath = dummyTestCoordinator.getReplicatedConfiguration().getOutgoingReplEventsDirectory();
    String[]entries = outgoingPath.list();
    for(String s: entries){
      File currentFile = new File(outgoingPath.getPath(),s);
      currentFile.delete();
    }
  }

  @Test
  public void testOutgoingEventInformationConstructor() throws IOException {

    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertNotNull(persistedEventInformation.getFinalEventFileName());
    Assert.assertNotNull(persistedEventInformation.getEventFile());
    Assert.assertNotNull(persistedEventInformation.getFileOutputStream());

    Assert.assertTrue(persistedEventInformation.getEventFile().getName().contains("events")
        && persistedEventInformation.getEventFile().getName().contains(".tmp"));

    Assert.assertTrue(persistedEventInformation.getFinalEventFileName().contains("events")
        && persistedEventInformation.getFinalEventFileName().contains(".json"));

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 0);
    Assert.assertEquals(persistedEventInformation.getProjectName(), "ProjectA");
  }



  @Test
  public void testGetFinalEventFileName() throws IOException {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    String eventTimestamp = dummyWrapper.getEventData().getEventTimestamp();
    String eventNanoTime = ObjectUtils.getHexStringOfLongObjectHash(
        Long.parseLong(dummyWrapper.getEventData().getEventNanoTime()));

    String objectHash = ObjectUtils.getHexStringOfIntObjectHash(dummyWrapper.hashCode());
    getProjectNameSha1(dummyWrapper.getProjectName());

    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);

    Assert.assertEquals(persistedEventInformation.getFinalEventFileName(),
        String.format(NEXT_EVENTS_FILE, eventTimeStr,
        dummyWrapper.getEventData().getNodeIdentity(),
            getProjectNameSha1(dummyWrapper.getProjectName()), objectHash));

  }


  @Test
  public void test_atomicRenameAndResetNullFinalName() throws IOException {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    persistedEventInformation.setFinalEventFileName(null);
    persistedEventInformation.atomicRenameTmpFilename();

    Assert.assertTrue(outgoingDir.exists());
    Assert.assertTrue(persistedEventInformation.getEventFile().exists());
    Assert.assertNull(persistedEventInformation.getFinalEventFileName());
  }


  @Test
  public void test_atomicRenameAndReset() throws IOException {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    persistedEventInformation.atomicRenameTmpFilename();

    Assert.assertTrue(outgoingDir.exists());
    Assert.assertFalse(persistedEventInformation.getEventFile().exists());
    Assert.assertTrue(new File(outgoingDir, persistedEventInformation.getFinalEventFileName()).exists());
  }


  @Test
  public void testTimeToWaitBeforeProposingExpired() throws IOException, InterruptedException {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), 5000);

    Thread.sleep(5000);

    Assert.assertTrue(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }



  @Test
  public void testTimeToWaitBeforeProposingExpired_NotExpired() throws Exception {
    Properties testingProperties = new Properties();

    testingProperties.put(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS, "20L");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), 20000);

    Assert.assertFalse(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }


  @Test
  public void testTimeToWaitBeforeProposingExpired_NegativeValue() throws Exception {
    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS, "-1");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);


    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration()
        .getMaxSecsToWaitBeforeProposingEvents(), -1000);
    Assert.assertTrue(persistedEventInformation.timeToWaitBeforeProposingExpired());
  }


  @Test
  public void testExceedsMaxEventsBeforeProposing() throws Exception {

    Properties testingProperties = new Properties();

    //Will always be expired if negative value
    testingProperties.put(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING, "2");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    EventWrapper dummyWrapper1 = createIndexEventWrapper("ProjectA");
    EventWrapper dummyWrapper2 = createIndexEventWrapper("ProjectA");
    EventWrapper dummyWrapper3 = createIndexEventWrapper("ProjectA");


    byte[] bytes1 = getEventBytes(dummyWrapper1);
    byte[] bytes2 = getEventBytes(dummyWrapper2);
    byte[] bytes3 = getEventBytes(dummyWrapper3);

    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper1);

    persistedEventInformation.writeEventsToFile(dummyWrapper1.getProjectName(), bytes1);
    persistedEventInformation.writeEventsToFile(dummyWrapper2.getProjectName(), bytes2);
    persistedEventInformation.writeEventsToFile(dummyWrapper3.getProjectName(), bytes3);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 3);

    Assert.assertTrue(persistedEventInformation.exceedsMaxEventsBeforeProposing());
  }




  @Test
  public void testSetFileReady_noEventsWritten() throws Exception {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 0);

    persistedEventInformation.setFileReady();
    Assert.assertFalse(persistedEventInformation.isFileOutputStreamClosed());
  }


  @Test
  public void testSetFileReady() throws Exception {
    EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
    PersistedEventInformation persistedEventInformation =
        new PersistedEventInformation(dummyTestCoordinator, dummyWrapper);


    byte[] bytes = getEventBytes(dummyWrapper);
    persistedEventInformation.writeEventsToFile(dummyWrapper.getProjectName(), bytes);

    Assert.assertEquals(persistedEventInformation.getNumEventsWritten().get(), 1);

    persistedEventInformation.setFileReady();
    Assert.assertTrue(persistedEventInformation.isFileOutputStreamClosed());
  }



  private byte[] getEventBytes(final EventWrapper eventWrapper) throws UnsupportedEncodingException {
    Gson gson = new GsonBuilder()
        .registerTypeAdapter(Supplier.class, new SupplierSerializer())
        .registerTypeAdapter(Event.class, new EventDeserializer())
        .registerTypeAdapter(Supplier.class, new SupplierDeserializer())
        .create();

    final String wrappedEvent = gson.toJson(eventWrapper) + '\n';
    return wrappedEvent.getBytes(ENC);
  }


  private EventWrapper createIndexEventWrapper(String projectName) throws IOException {
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(DateTime.now().getMillisOfSecond(), dummyTestCoordinator.getThisNodeIdentity());
    return GerritEventFactory.createReplicatedAccountIndexEvent(
        projectName, accountIndexEvent, ACCOUNT_INDEX_EVENT);
  }


  @AfterClass
  public static void shutdown(){
    dummyTestCoordinator.stop();

  }

}
