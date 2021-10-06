package com.google.gerrit.common.replication;

import com.google.gerrit.common.replication.feeds.ReplicatedOutgoingServerEventsFeed;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Properties;

public class ReplicatedOutgoingServerEventsFeedTest extends AbstractReplicationTesting {
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
  public void testCompleteRef(){
    Assert.assertEquals(ReplicatedOutgoingServerEventsFeed.completeRef(null), "");
    Assert.assertEquals(ReplicatedOutgoingServerEventsFeed.completeRef("foo"), "refs/heads/foo");
    Assert.assertEquals(ReplicatedOutgoingServerEventsFeed.completeRef("foo/bar"), "refs/heads/foo/bar");
    Assert.assertEquals(ReplicatedOutgoingServerEventsFeed.completeRef("refs/heads/foo"), "refs/heads/foo");
  }
}
