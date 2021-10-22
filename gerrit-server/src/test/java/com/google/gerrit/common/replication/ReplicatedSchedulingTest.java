package com.google.gerrit.common.replication;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.common.replication.processors.ReplicatedIncomingIndexEventProcessor.buildListOfMissingIds;
import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_INDEX_EVENT;

public class ReplicatedSchedulingTest extends AbstractReplicationTesting {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  public ReplicatedScheduling scheduling;

  @Before
  public void setupTest() throws Exception {
    // make sure we clear out and have a new coordinator for each test - sorry but otherwise we would need to be
    // clearing out lists which would change depend on ordering!
    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    scheduling = new ReplicatedScheduling(dummyTestCoordinator);
    HashMap<String, ReplicatedEventTask> eventFilesInProgress = scheduling.getCopyEventsFilesInProgress();

    for (Map.Entry eventTask : eventFilesInProgress.entrySet()) {
      scheduling.clearEventsFileInProgress((ReplicatedEventTask) eventTask.getValue(), false);
    }

    scheduling.getSkippedProjectsEventFiles().clear();

    Assert.assertNotNull(dummyTestCoordinator);
    Assert.assertNotNull(scheduling);
  }

  @After
  public void tearDown() {

    File incomingPath = dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory();
    String[] entries = incomingPath.list();
    for (String s : entries) {
      File currentFile = new File(incomingPath.getPath(), s);
      currentFile.delete();
    }
  }


  @Test
  public void testSchedulingOfSkippedEventsMaintainsOrderingOfEvents() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createIndexEventWrapper("SkipMe1");
    File eventInfoIndex0 = createDummyEventFile();
    scheduling.addSkippedProjectEventFile(eventInfoIndex0, dummyWrapper.getProjectName());
    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper.getProjectName());

    Assert.assertTrue(scheduling.containsSkippedProjectEventFiles(dummyWrapper.getProjectName()));
    LinkedHashSet<File> skippedInfo1 = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());
    Assert.assertEquals(1, skippedInfo1.size());
    Assert.assertEquals(eventInfoIndex0, skippedInfo1.iterator().next());
    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));

    // Lets make another event file and skip it also.
    EventWrapper dummyWrapper2 = createIndexEventWrapper("SkipMe2");
    File eventInfoIndex1 = createDummyEventFile();
    File eventInfoIndex2 = createDummyEventFile();
    File eventInfoIndex3 = createDummyEventFile();
    File eventInfoIndex4 = createDummyEventFile();

    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper2.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex2, dummyWrapper.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex3, dummyWrapper2.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex4, dummyWrapper.getProjectName());

    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper2.getProjectName());
    LinkedHashSet<File> skippedInfo2 = scheduling.getSkippedProjectEventFilesList(dummyWrapper2.getProjectName());


    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));
    skippedInfo1 = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());

    System.out.println(Arrays.toString(skippedInfo1.toArray()));
    System.out.println(Arrays.toString(skippedInfo2.toArray()));

    Assert.assertEquals(3, skippedInfo1.size());
    Assert.assertEquals(eventInfoIndex0, skippedInfo1.toArray()[0]);
    Assert.assertEquals(eventInfoIndex2, skippedInfo1.toArray()[1]);
    Assert.assertEquals(eventInfoIndex4, skippedInfo1.toArray()[2]);

    Assert.assertEquals(2, skippedInfo2.size());
    Assert.assertEquals(eventInfoIndex1, skippedInfo2.toArray()[0]);
    Assert.assertEquals(eventInfoIndex3, skippedInfo2.toArray()[1]);

  }


  @Test
  public void testSchedulingOfProjectWhenFull() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    {
      EventWrapper dummyWrapper = createIndexEventWrapper("All-Users");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
    }

    {
      EventWrapper dummyWrapper = createIndexEventWrapper("All-Projects");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
      Assert.assertEquals(2, scheduling.getNumberOfCoreProjectsInProgress());
    }


    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ProjectA");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }


    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ProjectB");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ThisShouldFail!!");
      ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNull(replicatedEventTask);
    }

  }

  /**
   * Test core reserved threads are except when scheduling normal project.
   *
   * @throws IOException
   */
  @Test
  public void testSchedulingOfProjectWhenFullButReservedThreadsAvailable() throws IOException {
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());

    ReplicatedEventTask replicatedEventTask;
    {
      EventWrapper dummyWrapper = createIndexEventWrapper("All-Users");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ProjectX");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(2, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ProjectY");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNotNull(replicatedEventTask);
      Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());
      Assert.assertEquals(3, scheduling.getNumEventFilesInProgress());
      Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    }

    // lastly when full - so no more general threads - it should return null event though
    // there is another thread for all projects - its reserved so can't be used.
    {
      EventWrapper dummyWrapper = createIndexEventWrapper("ProjectZ");
      replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
      Assert.assertNull(replicatedEventTask);
    }
  }

  @Test
  public void testSimpleBuildMissingIdsList(){

    Collection<Integer> deletedIdsList = Arrays.asList(1, 3); // make sure we skip say no2 so make sure there is no ordering assumptions.
    Collection<Integer> requestedIdsList = Arrays.asList(1, 2, 3, 5, 6); // using a set so we have each id only once.

    // build the list of missing items or items yet to be processed
    Collection<Integer> listOfMissingIds = buildListOfMissingIds(requestedIdsList, deletedIdsList);

    for ( int thisId : requestedIdsList){
      Assert.assertTrue( deletedIdsList.contains(thisId) ? !listOfMissingIds.contains(thisId) : listOfMissingIds.contains(thisId));
    }
  }

  /**
   * Test core reserved threads are except when scheduling normal project.
   *
   * @throws IOException
   */
  @Test
  public void testSchedulingOfProjectWhenSkippedShouldNotSchedule() throws IOException {

    EventWrapper dummyWrapper = createIndexEventWrapper("All-Users");
    File dummyEventFile = createDummyEventFile();

    ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, dummyEventFile);
    Assert.assertNotNull(replicatedEventTask);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());

    // Mark to skip project A for all future work - make sure it cant be scheduled.

    dummyWrapper = createIndexEventWrapper("ProjectA");
    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper.getProjectName());

    Assert.assertEquals("ProjectA", dummyWrapper.getProjectName());
    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));
    Assert.assertTrue("Failed should skip found this backoffInfo: " + scheduling.getSkipThisProjectForNowBackoffInfo(dummyWrapper.getProjectName()),
        scheduling.shouldStillSkipThisProjectForNow(dummyWrapper.getProjectName()));

    ReplicatedEventTask nonScheduledTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
    // shouldn't of scheduled - check null.
    Assert.assertNull(nonScheduledTask);
  }

  /**
   * creates a dummy event file in following format
   * events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEventContents>.json
   *
   * @return File - the created event file
   * @throws IOException
   */
  private File createDummyEventFile() throws IOException {
    // name these events correctly, maybe even stick some content in them later??
    return createDummyEventFile("dummyEventFile");
  }

  /**
   * creates a dummy event file in following format
   * events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEventContents>.json
   *
   * @return File - the created event file
   * @throws IOException
   */
  private File createDummyEventFile(final String someNamePrefix) throws IOException {
    // name these events correctly, maybe even stick some content in them later??
    return File.createTempFile(someNamePrefix, null, dummyTestCoordinator.getReplicatedConfiguration().getIncomingReplEventsDirectory());
  }

  /**
   * Test core reserved threads are except when scheduling normal project.
   *
   * @throws IOException
   */
  @Test
  public void testSchedulingOfProjectWhenWorkSkippedAlreadySwapsOutNextEventToProcess() throws IOException {
    EventWrapper dummyWrapper = createIndexEventWrapper("All-Users");
    ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());

    Assert.assertNotNull(replicatedEventTask);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());

    // Mark to skip project A for all future work - make sure it cant be scheduled.
    File firstSkippedEventFile = createDummyEventFile();
    File nextSkippedEventFile = createDummyEventFile();
    File nextEventFile = createDummyEventFile();

    dummyWrapper = createIndexEventWrapper("ProjectA");
    scheduling.addSkippedProjectEventFile(firstSkippedEventFile, dummyWrapper.getProjectName());

    dummyWrapper = createIndexEventWrapper("ProjectA");
    scheduling.addSkippedProjectEventFile(nextSkippedEventFile, dummyWrapper.getProjectName());

    // This should not be scheduled - it should go on end of list!
    replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, nextEventFile);
    Assert.assertNotNull(replicatedEventTask);

    // now make sure that we didn't just schedule the next event file but instead we swapped it out
    // for the skipped one.
    Assert.assertEquals(firstSkippedEventFile.getName(), replicatedEventTask.getEventsFileToProcess().getName());

    // get the list and check last one also.
    LinkedHashSet skippedList = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());
    Assert.assertEquals(2, skippedList.size());
    Assert.assertEquals(nextSkippedEventFile, skippedList.toArray()[0]);
    Assert.assertEquals(nextEventFile, skippedList.toArray()[1]);
  }

  @Test
  public void testAddSkippedProjectEventFileMaintainsOrder() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> simpleList = new ArrayList<>();
    final String projectName = "TestMe";
    for (int index = 0; index < 10; index++) {
      File newFile = createDummyEventFile("testDummyOrdering" + index);
      simpleList.add(newFile);
      // make sure the list isn't reordering as we add.
      Assert.assertEquals(newFile, simpleList.get(index));
      scheduling.addSkippedProjectEventFile(newFile, projectName);
    }

    // Now lets see that our add to skipped list maintained the same order as out local array.
    LinkedHashSet<File> skippedEvents = scheduling.getSkippedProjectEventFilesList(projectName);
    Assert.assertArrayEquals(simpleList.toArray(), skippedEvents.toArray());

    // try plucking off first item must match
    Assert.assertEquals(simpleList.iterator().next(), skippedEvents.iterator().next());

    // try iterator - again must match .
    int index = 0;
    for (File skippedFile : skippedEvents) {
      File sourceFile = simpleList.get(index);
      Assert.assertEquals(sourceFile, skippedFile);
      index++;
    }
  }


  @Test
  public void testAddProjectWIPEventShallowCopyIsDirtyCopy() throws IOException {

    EventWrapper dummyWrapper = createIndexEventWrapper("All-Users");
    ReplicatedEventTask replicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
    Assert.assertNotNull(replicatedEventTask);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTask.getProjectname());
    Assert.assertEquals(1, scheduling.getNumberOfCoreProjectsInProgress());

    scheduling.addForTestingInProgress(replicatedEventTask);


    dummyWrapper = createIndexEventWrapper("All-Projects");
    ReplicatedEventTask replicatedEventTaskAllProjects = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, createDummyEventFile());
    Assert.assertNotNull(replicatedEventTaskAllProjects);
    Assert.assertEquals(dummyWrapper.getProjectName(), replicatedEventTaskAllProjects.getProjectname());
    Assert.assertEquals(2, scheduling.getNumberOfCoreProjectsInProgress());

    scheduling.addForTestingInProgress(replicatedEventTaskAllProjects);

    HashMap<String, ReplicatedEventTask> dirtyWIP = scheduling.getCopyEventsFilesInProgress();

    // iteration -> file a ( scheduled file a )  dirtyWIP = file.
    // iteration 2 ->
    // file a, ( dirty WIP )
    // file b
    //  ( the remote worker gets lock -> deletes file on disk, and removes from WIP ).
    // dirty wip contains Filea after remove of real WIP (file a).
    Assert.assertEquals(2, dirtyWIP.size());

    // remove Filea - like remote worker has finished.
    scheduling.clearEventsFileInProgress(replicatedEventTask, false);
    Assert.assertEquals(2, dirtyWIP.size());
    Assert.assertEquals(1, scheduling.getNumEventFilesInProgress());
  }

  /**
   * Test equals() and hashcode() contracts.
   */
  @Test
  @Ignore
  public void equalsContract() {
    EqualsVerifier.forClass(ReplicatedScheduling.class).suppress(Warning.NULL_FIELDS, Warning.ANNOTATION).verify();
  }

  private EventWrapper createIndexEventWrapper(String projectName) throws IOException {
    AccountIndexEvent accountIndexEvent = new AccountIndexEvent(DateTime.now().getMillisOfSecond(), dummyTestCoordinator.getThisNodeIdentity());
    return GerritEventFactory.createReplicatedAccountIndexEvent(
        projectName, accountIndexEvent, ACCOUNT_INDEX_EVENT);
  }


}
