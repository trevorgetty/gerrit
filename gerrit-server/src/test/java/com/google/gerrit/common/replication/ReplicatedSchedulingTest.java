package com.google.gerrit.common.replication;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.GerritEventFactory;
import com.google.gerrit.common.replication.workers.ReplicatedIncomingEventWorker;
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
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;

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
  public void testCopyOfWIPMaintainsEntries() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createIndexEventWrapper("SkipMe1");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = (File[]) Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray();
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];

    // Lets dummy creation of replicated tasts - we could just attempt schedule - easy route.
    // It should also add to WIP!!!
    final ReplicatedEventTask scheduledTaskWIP = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, eventInfoIndex0);

    // check is in progress first!!!
    Assert.assertTrue(scheduling.containsEventsFileInProgress(dummyWrapper.getProjectName()));

    HashMap<String, ReplicatedEventTask> copyWIP = scheduling.getCopyEventsFilesInProgress();
    // we build a copy of the WIP as a collection for FILE based quick lookups - check this works as runtime expects it.
    Collection<File> dirtyCopyOfWIPFiles = ReplicatedIncomingEventWorker.buildDirtyWIPFiles(copyWIP);

    // ensure our copy can be found directly
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
    Assert.assertTrue(scheduling.containsEventsFileInProgress(dummyWrapper.getProjectName()));

    // make sure it doesn't find something thats not there.
    Assert.assertFalse(dirtyCopyOfWIPFiles.contains(eventInfoIndex1));

    // Now we need to make sure deletion from the core WIP doesn't actually delete it from our copy.
    scheduling.clearEventsFileInProgress(scheduledTaskWIP, false);
    // it should be gone locally in the wip real hashmap, but not the copy.
    Assert.assertFalse(scheduling.containsEventsFileInProgress(dummyWrapper.getProjectName()));
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
  }


  @Test
  public void testSkippedEventFilesCannotHoldDuplicates() throws IOException {
    // this would start failing earlier if someone changes the coreProjects, so just checking otherwise change this test!!
    Assert.assertNotNull(scheduling);
    Assert.assertEquals(2, dummyTestCoordinator.getReplicatedConfiguration().getCoreProjects().size());
    Assert.assertEquals(4, dummyTestCoordinator.getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());
    Assert.assertEquals(0, scheduling.getNumEventFilesInProgress());
    Assert.assertEquals(0, scheduling.getNumberOfCoreProjectsInProgress());

    EventWrapper dummyWrapper = createIndexEventWrapper("SkipMe1");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = (File[]) Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray();
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];

    // Lets dummy creation of replicated tasts - we could just attempt schedule - easy route.
    // It should also add to WIP!!!
    final ReplicatedEventTask scheduledTaskWIP = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, eventInfoIndex0);

    // check is in progress first!!!
    Assert.assertTrue(scheduling.containsEventsFileInProgress(dummyWrapper.getProjectName()));

    HashMap<String, ReplicatedEventTask> copyWIP = scheduling.getCopyEventsFilesInProgress();
    // we build a copy of the WIP as a collection for FILE based quick lookups - check this works as runtime expects it.
    Collection<File> dirtyCopyOfWIPFiles = ReplicatedIncomingEventWorker.buildDirtyWIPFiles(copyWIP);

    // ensure our copy can be found directly
    Assert.assertTrue(dirtyCopyOfWIPFiles.contains(eventInfoIndex0));
    Assert.assertTrue(scheduling.containsEventsFileInProgress(dummyWrapper.getProjectName()));
    Assert.assertFalse(scheduling.containsSkippedProjectEventFiles(dummyWrapper.getProjectName()));

    // Now if we try to schedule the same file again it should go into the skipped list.

    final ReplicatedEventTask nothingTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, eventInfoIndex0);

    // make sure it doesn't find something thats not there.
    Assert.assertNull(nothingTask);

    // now we should have the index0 not in the skipped list as it is already in progress!
    Assert.assertFalse(scheduling.containsSkippedProjectEventFiles(dummyWrapper.getProjectName()));
    // lets try prepend and append and check we can't duplicate entries
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper.getProjectName());
    Assert.assertEquals(1, scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName()).size());
    // this shouldn't add twice.
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper.getProjectName());
    Assert.assertEquals(1, scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName()).size());

    // ok lets prepend the earlier file I know its in progress but I am bypassing WIP check to test skipped handling!!
    scheduling.prependSkippedProjectEventFile(eventInfoIndex0, dummyWrapper.getProjectName());
    Assert.assertEquals(2, scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName()).size());
    Assert.assertEquals(eventInfoIndex0, scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName()).getFirst());
    Assert.assertEquals(eventInfoIndex1, scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName()).getLast());
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

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take 5 randomly named event files, and order correctly as if by timestamp
    File[] events = (File[]) Arrays.asList(createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile(), createDummyEventFile()).toArray();
    Arrays.sort(events);

    // Now they are sorted, lets take them and assign correctly to our named items, so we can play arround with
    // ordering correctly
    final File eventInfoIndex0 = events[0];
    final File eventInfoIndex1 = events[1];
    final File eventInfoIndex2 = events[2];
    final File eventInfoIndex3 = events[3];
    final File eventInfoIndex4 = events[4];

    scheduling.addSkippedProjectEventFile(eventInfoIndex0, dummyWrapper.getProjectName());
    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper.getProjectName());

    Assert.assertTrue(scheduling.containsSkippedProjectEventFiles(dummyWrapper.getProjectName()));
    Deque<File> skippedInfo1 = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());
    Assert.assertEquals(1, skippedInfo1.size());
    Assert.assertEquals(eventInfoIndex0, skippedInfo1.getFirst());
    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));

    // add 2 more skipped events to skipme1
    scheduling.addSkippedProjectEventFile(eventInfoIndex2, dummyWrapper.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex4, dummyWrapper.getProjectName());

    // Lets make another event file and skip it also for a different project to ensure they dont
    // overlap
    EventWrapper dummyWrapper2 = createIndexEventWrapper("SkipMe2");

    // add 2 to skipme2
    scheduling.addSkippedProjectEventFile(eventInfoIndex1, dummyWrapper2.getProjectName());
    scheduling.addSkippedProjectEventFile(eventInfoIndex3, dummyWrapper2.getProjectName());


    scheduling.addSkipThisProjectsEventsForNow(dummyWrapper2.getProjectName());
    Deque<File> skippedInfo2 = scheduling.getSkippedProjectEventFilesList(dummyWrapper2.getProjectName());


    Assert.assertTrue(scheduling.containsSkipThisProjectForNow(dummyWrapper.getProjectName()));
    skippedInfo1 = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());

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
  public void testSimpleBuildMissingIdsList() {

    Collection<Integer> deletedIdsList = Arrays.asList(1, 3); // make sure we skip say no2 so make sure there is no ordering assumptions.
    Collection<Integer> requestedIdsList = Arrays.asList(1, 2, 3, 5, 6); // using a set so we have each id only once.

    // build the list of missing items or items yet to be processed
    Collection<Integer> listOfMissingIds = buildListOfMissingIds(requestedIdsList, deletedIdsList);

    for (int thisId : requestedIdsList) {
      Assert.assertTrue(deletedIdsList.contains(thisId) ? !listOfMissingIds.contains(thisId) : listOfMissingIds.contains(thisId));
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
        scheduling.shouldStillSkipThisProjectForNow(dummyEventFile, dummyWrapper.getProjectName()));

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

    // numbering the file for ordering correctness.
    File eventFile0 = createDummyEventFile("eventtest-0");
    File eventFile1 = createDummyEventFile("eventtest-1");
    File eventFile2 = createDummyEventFile("eventtest-2");

    // Note we use random UUIDs, so we need to ensure correct ordering for below to work its not just a FIFO now,
    // its a prioritised queue to ensure we always keep the correct first event at HEAD of the queue.
    // So take x randomly named event files, and order correctly as if by timestamp  I am putting them in the list
    // in incorrect order, just to make sure the sort is correctly sorting to 0, 1, 2.
    File[] events = (File[]) Arrays.asList( eventFile1, eventFile0, eventFile2).toArray();
    Arrays.sort(events);

    // check sort is correct
    Assert.assertEquals(eventFile0, events[0]);
    Assert.assertEquals(eventFile1, events[1]);
    Assert.assertEquals(eventFile2, events[2]);

    // Check that the files being ordered have appeared correctly as file0, then file1.
    // Note later we will ask for file 2, and it should swap out for file0
    final String projectName = "ProjectA";

    // Now add the first 2 event files to the skipped list, so when we try to schedule file2 it will
    // be able to schedule but should swap out for an already skipped over event with a higher ordering.
    scheduling.addSkippedProjectEventFile(eventFile0, projectName);
    scheduling.addSkippedProjectEventFile(eventFile1, projectName);

    // This should not be scheduled - it should go on end of list!
    EventWrapper dummyWrapper = createIndexEventWrapper(projectName);
    final ReplicatedEventTask scheduledReplicatedEventTask = scheduling.tryScheduleReplicatedEventsTask(dummyWrapper, eventFile2);
    Assert.assertNotNull(scheduledReplicatedEventTask);

    // now make sure that we didn't just schedule the next event file but instead we swapped it out
    // for the first skipped one as its at the HEAD of the FIFO.
    Assert.assertEquals(eventFile0.getName(), scheduledReplicatedEventTask.getEventsFileToProcess().getName());

    // get the list and check that we have 2 items in the list, which should be file 1, and file 2.  (file0 got swapped out ).
    Deque<File> skippedList = scheduling.getSkippedProjectEventFilesList(dummyWrapper.getProjectName());
    Assert.assertEquals(2, skippedList.size());
    Assert.assertEquals(eventFile1, skippedList.toArray()[0]);
    Assert.assertEquals(eventFile2, skippedList.toArray()[1]);
  }

  @Test
  public void testAddSkippedProjectEventFileMaintainsOrder() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> simpleList = new ArrayList<>();
    final String projectName = "TestMe";

    // keep this file outside of the add list, so we can add later but it should bubble to the top of the priority
    // queue as its ordering is earlier.
    File firstEventsFile = createDummyEventFile("testDummyOrdering-0" + 0);

    for (int index = 0; index < 10; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      simpleList.add(newFile);
      // make sure the list isn't reordering as we add.
      Assert.assertEquals(newFile, simpleList.get(index));
      scheduling.addSkippedProjectEventFile(newFile, projectName);
    }

    // Now lets see that our add to skipped list maintained the same order as out local array.
    Deque<File> skippedEvents = scheduling.getSkippedProjectEventFilesList(projectName);
    Assert.assertArrayEquals(simpleList.toArray(), skippedEvents.toArray());

    // try plucking off first item must match
    Assert.assertEquals(simpleList.iterator().next(), skippedEvents.getFirst());

    // try iterator - again must match .
    int index = 0;
    for (File skippedFile : skippedEvents) {
      File sourceFile = simpleList.get(index);
      Assert.assertEquals(sourceFile, skippedFile);
      index++;
    }

    // Now lets PREPEND the first one we created, and check we have one more but its not at the end, its at the start!
    scheduling.prependSkippedProjectEventFile(firstEventsFile, projectName);

    File nextSkippedToBeProcessed = scheduling.getFirstSkippedProjectEventFile(projectName);
    Assert.assertEquals(firstEventsFile, nextSkippedToBeProcessed);

    // make sure this is not POP off the queue, its only a PEEK.
    File sameSkippedFile = scheduling.getFirstSkippedProjectEventFile(projectName);
    Assert.assertEquals(firstEventsFile, sameSkippedFile);
  }


  @Test
  public void testPrependSkippedProjectEventFileMaintainsOrder() throws IOException {
    // check ordering of files in the event directory - make names and randomly stick them into the list
    // then check they are the order we added them.
    List<File> simpleList = new ArrayList<>();
    final String projectName = "TestMe";

    // keep this file outside of the add list, so we can add later but it should bubble to the top of the priority
    // queue as its ordering is earlier.
    File firstEventsFile = createDummyEventFile("testDummyOrdering-0" + 0);

    for (int index = 0; index < 10; index++) {
      File newFile = createDummyEventFile("testDummyOrdering-1" + index);
      simpleList.add(newFile);
      // make sure the list isn't reordering as we add.
      Assert.assertEquals(newFile, simpleList.get(index));
      scheduling.addSkippedProjectEventFile(newFile, projectName);
    }

    // Now lets see that our add to skipped list maintained the same order as out local array.
    Deque<File> skippedEvents = scheduling.getSkippedProjectEventFilesList(projectName);
    Assert.assertArrayEquals(simpleList.toArray(), skippedEvents.toArray());

    // try plucking off first item must match
    Assert.assertEquals(simpleList.iterator().next(), skippedEvents.getFirst());

    // try iterator - again must match .
    int index = 0;
    for (File skippedFile : skippedEvents) {
      File sourceFile = simpleList.get(index);
      Assert.assertEquals(sourceFile, skippedFile);
      index++;
    }

    // Now lets add the first one we created, and check we have one more but its not at the end, its at the start!
    // I EXpect this to fail now - MUST be prepend.
    scheduling.prependSkippedProjectEventFile(firstEventsFile, projectName);

    File nextSkippedToBeProcessed = scheduling.getFirstSkippedProjectEventFile(projectName);
    Assert.assertEquals(firstEventsFile, nextSkippedToBeProcessed);

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
