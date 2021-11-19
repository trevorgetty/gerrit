package com.google.gerrit.common.replication;

import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;

import java.util.Properties;

import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_NUM_EVENTS_RETRIES;

public class ProjectBackoffPeriodTest extends AbstractReplicationTesting {

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @BeforeClass
  public static void beforeClass() throws Exception {
    AbstractReplicationTesting.beforeClass();

    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_MAX_NUM_EVENTS_RETRIES, "10");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD, "0.5");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_CEILING_PERIOD, "10");

    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
  }

  @AfterClass
  public static void shutdown() {
    dummyTestCoordinator.stop();
  }

  @Test
  public void testBackoffPeriodConfiguration() {
    // check the default sequence makes sense.
    Assert.assertEquals(10, dummyTestCoordinator.getReplicatedConfiguration().getMaxIndexBackoffRetries());
    Assert.assertEquals(500, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffInitialPeriodMs());
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffCeilingPeriodMs());

    // check the sequence matches our expectations of doubling per item.
    Assert.assertEquals(500, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(1));
    Assert.assertEquals(1000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(2));
    Assert.assertEquals(2000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(3));
    Assert.assertEquals(4000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(4));
    Assert.assertEquals(8000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(5));
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(6));
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(7));
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(8));
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(9));
    Assert.assertEquals(10000, dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(10));
    // make sure this throws.
    try {
      dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffPeriodMs(11);
      Assert.assertTrue("this shouldn't not be reached - more than X retries..", false);
    } catch (IndexOutOfBoundsException e) {
      Assert.assertTrue("Passed - threw correctly.", true);
    }
  }

  @Test
  public void testBackoffPeriodElements() {
    // check the default sequence makes sense.
    final String projectName = StringUtils.createUniqueString("testMe");
    ProjectBackoffPeriod projectBackoffPeriod = new ProjectBackoffPeriod(projectName, dummyTestCoordinator.getReplicatedConfiguration());

    Assert.assertEquals(1, projectBackoffPeriod.getNumFailureRetries());
    Assert.assertEquals(projectName, projectBackoffPeriod.getProjectName());
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffInitialPeriodMs(),
        projectBackoffPeriod.getCurrentBackoffPeriodMs());
  }

  @Test
  public void testBackoffPeriodFailureIncreasesBackoff() {
    // check the default sequence makes sense.
    final String projectName = StringUtils.createUniqueString("testMe");
    ProjectBackoffPeriod projectBackoffPeriod = new ProjectBackoffPeriod(projectName, dummyTestCoordinator.getReplicatedConfiguration());

    Assert.assertEquals(1, projectBackoffPeriod.getNumFailureRetries());
    Assert.assertEquals(projectName, projectBackoffPeriod.getProjectName());
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffInitialPeriodMs(),
        projectBackoffPeriod.getCurrentBackoffPeriodMs());

    // Now lets fail and check it bumped the counter.
    projectBackoffPeriod.updateFailureInformation();

    Assert.assertEquals(2, projectBackoffPeriod.getNumFailureRetries());
    Assert.assertEquals(projectName, projectBackoffPeriod.getProjectName());
    Assert.assertEquals(dummyTestCoordinator.getReplicatedConfiguration().getIndexBackoffInitialPeriodMs() * 2,
        projectBackoffPeriod.getCurrentBackoffPeriodMs());
  }

  @Test
  public void testBackoffPeriodMaxFailureRetriesAndBeyond() {
    // check the default sequence makes sense.
    final String projectName = StringUtils.createUniqueString("testMe");
    ProjectBackoffPeriod projectBackoffPeriod = new ProjectBackoffPeriod(projectName, dummyTestCoordinator.getReplicatedConfiguration());

    Assert.assertEquals(1, projectBackoffPeriod.getNumFailureRetries());

    // simple check after we get above our max retry counter of 10, it shouldn't continue incrementing, but
    // should stop at that point.
    for (int index = 1; index <= dummyTestCoordinator.getReplicatedConfiguration().getMaxIndexBackoffRetries() + 5; index++) {
      projectBackoffPeriod.updateFailureInformation();
    }
    // so we should still be at max retries here, check start time still updates though.
    Assert.assertEquals(projectBackoffPeriod.getNumFailureRetries(), dummyTestCoordinator.getReplicatedConfiguration().getMaxIndexBackoffRetries());
  }

  @Test
  public void testBackoffUpdateFailureInformationStartTimeBeyondMaxRetries() throws Exception {
    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_MAX_NUM_EVENTS_RETRIES, "8");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD, "0.5");
    testingProperties.put(GERRIT_EVENTS_BACKOFF_CEILING_PERIOD, "5");

    TestingReplicatedEventsCoordinator localTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);
    // check our config worked.
    Assert.assertEquals(8, localTestCoordinator.getReplicatedConfiguration().getMaxIndexBackoffRetries());
    // note our periods are in Ms, not the seconds which we spec it out in.
    Assert.assertEquals(500, localTestCoordinator.getReplicatedConfiguration().getIndexBackoffInitialPeriodMs());
    Assert.assertEquals(5000, localTestCoordinator.getReplicatedConfiguration().getIndexBackoffCeilingPeriodMs());

    // Now lets check it impacts our retries, so as num retires is 8, we should be able to fail 8 times, then exception.
    ProjectBackoffPeriod projectBackoffPeriod = new ProjectBackoffPeriod("testme", localTestCoordinator.getReplicatedConfiguration());

    // calling more than the allowed amount, but catching it and continue - to test the actual backoff period didn't get incremented.
    for (int index = 1; index <= 10; index++) {
      projectBackoffPeriod.updateFailureInformation();
    }

    Assert.assertEquals(localTestCoordinator.getReplicatedConfiguration().getMaxIndexBackoffRetries(), projectBackoffPeriod.getNumFailureRetries());

    // Check start time - then call update, and check again.
    long startTimeForThisCheck = projectBackoffPeriod.getStartTimeInMs();
    Thread.sleep(1000); // make sure we wait some time - was getting random errors when processed quickly.
    projectBackoffPeriod.updateFailureInformation();
    long startTimeForLaterCheck = projectBackoffPeriod.getStartTimeInMs();

    Assert.assertNotEquals(startTimeForLaterCheck, startTimeForThisCheck);
    Assert.assertTrue(startTimeForLaterCheck > startTimeForThisCheck);
  }

  /**
   * Test equals() and hashcode() contracts.
   */
  @Test
  @Ignore
  public void equalsContract() {
    EqualsVerifier.forClass(ProjectBackoffPeriod.class).suppress(Warning.NULL_FIELDS, Warning.NONFINAL_FIELDS).verify();
  }
}
