package com.google.gerrit.common.replication;

import com.google.gerrit.common.GerritEventFactory;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.BeforeClass;

import java.util.Properties;

import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;

public class AbstractReplicationTesting {

  static TestingReplicatedEventsCoordinator dummyTestCoordinator;

  @BeforeClass
  public static void beforeClass() throws Exception {
    // make sure to clear - really we want to call disable in before class and only enable for one test.
    SingletonEnforcement.clearAll();
    SingletonEnforcement.setDisableEnforcement(true);

    Properties testingProperties = new Properties();

    // SET our pool to 2 items, plus the 2 core projects.
    testingProperties.put(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, "2");
    dummyTestCoordinator = new TestingReplicatedEventsCoordinator(testingProperties);

    // Initialize our event factory gson...
    GerritEventFactory.setupEventWrapper();
  }
}
