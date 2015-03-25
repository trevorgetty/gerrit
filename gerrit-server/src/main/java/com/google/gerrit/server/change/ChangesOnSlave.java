package com.google.gerrit.server.change;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.SlaveWait;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gwtorm.server.OrmException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS_POSIX_Java6;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a new row in the slave_waits table (using the master DB) and
 * then waits for that row to be created on the slave.
 * This way we are sure that the data we are interested in is on the slave
 * when we need it (i.e. before the index to Lucene is called)
 * 
 * ... This will need to find in the database these tables:
 * 
 
 CREATE TABLE `slave_wait_id` (
  `s` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  UNIQUE KEY `s` (`s`)
 );

CREATE TABLE `slave_waits` (
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `status` char(1) NOT NULL DEFAULT '',
  `wait_id` int(11) NOT NULL DEFAULT '0',
  PRIMARY KEY (`wait_id`)
);

 * 
 *
 * @author antoniochirizzi
 */
public class ChangesOnSlave {
  public static final long TOTAL_TIME_TO_WAIT = 5 * 60 * 1000L; // 5 minutes
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String SLAVE_MODE_SLEEP_TIME = "gerrit.db.slavemode.sleepTime";

  private static final Logger log = LoggerFactory.getLogger(ChangesOnSlave.class);
  private static long sleepTimeBetweenChecks = 100L;
  public static final boolean DELETE_AFTER_RETRIEVAL = true;
  
  static {
    readConfiguration();
  }
  
  /**
   * Will create a new slavewait id and wait for that to appear on the database;
   * Since it will be written to the master, the read should gather the value from the slave (if we are using mysql-proxy).
   * After retrieval it will delete the added row from the table, but it won't delete the sequence (which is not possible with the
   * ORM at the moment.
   * Anyhow for about 220K markers, this is the space needed: about 5MB for each table:
   * 
      [root@dger04 gitms-gerrit-longtests]# ll -h /var/lib/mysql/reviewdb/*wait*
      -rw-rw---- 1 mysql mysql 8.4K Jan 12 10:58 /var/lib/mysql/reviewdb/slave_wait_id.frm
      -rw-rw---- 1 mysql mysql 2.0M Jan 13 15:22 /var/lib/mysql/reviewdb/slave_wait_id.MYD
      -rw-rw---- 1 mysql mysql 3.2M Jan 13 15:22 /var/lib/mysql/reviewdb/slave_wait_id.MYI
      -rw-rw---- 1 mysql mysql 8.5K Jan 12 11:01 /var/lib/mysql/reviewdb/slave_waits.frm
      -rw-rw---- 1 mysql mysql 2.2M Jan 13 15:22 /var/lib/mysql/reviewdb/slave_waits.MYD
      -rw-rw---- 1 mysql mysql 2.3M Jan 13 15:22 /var/lib/mysql/reviewdb/slave_waits.MYI
   * 
   * This should be not executed in a non master-slave situation
   *
   * @param db
   * @return
   */
  @SuppressWarnings("SleepWhileInLoop")
  public static final int createAndWaitForSlaveIdWithCommit(ReviewDb db) {
    long timeSoFar = 0;

    if (sleepTimeBetweenChecks <= 0) {
      return -1;
    }
    
    try {
      log.debug("Creating new wait id...");
      SlaveWait.Id id = new SlaveWait.Id(db.nextSlaveWaitId());
      db.slaveWaits().beginTransaction(id);
      db.slaveWaits().insert(Collections.singleton(new SlaveWait(id, null)));
      db.commit();
      log.debug("Created new wait id  {}, now going to wait...",id.get());

      int timesToLog = 0;
      while (timeSoFar <= TOTAL_TIME_TO_WAIT) {
        timeSoFar += sleepTimeBetweenChecks;
        // let's try to get the row from the local slave DB
        SlaveWait slaveWait = db.slaveWaits().get(id);
        if (slaveWait != null) {
          log.debug("SlaveId {} found!", id);
          if (DELETE_AFTER_RETRIEVAL) {
            db.slaveWaits().delete(Collections.singleton(slaveWait));
            db.commit();
            log.debug("SlaveId {} deleted", id);
          }
          return id.get();
        } else {
          try {
            if (++timesToLog % 20 == 0) {
              log.warn("SlaveWait {} not found, still waiting after {} ms", new Object[] {id,timeSoFar});
            } else {
              log.debug("SlaveWait {} not found, waiting", id);
            }
            Thread.sleep(sleepTimeBetweenChecks);
          } catch (InterruptedException ex) {
          }
        }
      }
      log.error("SlaveId {} NOT FOUND on slave after {} ms!", new Object[] {id,timeSoFar});
    } catch (Exception e) {
      log.error("While waiting for SlaveWait to appear...", e);
      try {
        db.rollback();
      } catch(OrmException f) {
        log.error("While rollbacking for SlaveWait to appear...", f);
      }
    }
    return 0;
  }
  public static final int createAndWaitForSlaveIdWithCommit(ReviewDb db, Change.Id changeId) {
    log.debug("Creating new wait id for changeId {}...",changeId.get());
    int waitId = createAndWaitForSlaveIdWithCommit(db);
    log.debug("Created and deleted wait id {} for changeId {}",new Object[] {waitId,changeId.get()});
    return waitId;
  }
  
  private static void readConfiguration() {
    try {
      String gitConfigLoc = System.getenv("GIT_CONFIG");
 
      if (gitConfigLoc == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }
      
      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), new FS_POSIX_Java6());
      try {
        config.load();
      } catch (ConfigInvalidException e) {
        // Configuration file is not in the valid format, throw exception back.
        throw new IOException(e);
      }

      String appProperties = config.getString("core", null, "gitmsconfig");
      File applicationProperties = new File(appProperties);
      if(!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (1) " + applicationProperties);
        applicationProperties = new File(DEFAULT_MS_APPLICATION_PROPERTIES,"application.properties");
      }
   
      if(!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (2) " + applicationProperties);
        sleepTimeBetweenChecks = -1;
      } else {
        try {
          Properties props = new Properties();
          props.load(new FileInputStream(applicationProperties));
          String sleepTimeStr = props.getProperty(SLAVE_MODE_SLEEP_TIME,""+sleepTimeBetweenChecks);
          try {
            sleepTimeBetweenChecks = Integer.parseInt(sleepTimeStr);
            if (sleepTimeBetweenChecks > 0 && sleepTimeBetweenChecks < 10) {
              sleepTimeBetweenChecks = 10; // min time to wait
            }
            log.info("{}={}",new Object[] {SLAVE_MODE_SLEEP_TIME,sleepTimeBetweenChecks});
            if (sleepTimeBetweenChecks > 0) {
              log.info("If this is a Gerrit connecting to a single or MASTER database configuration you should "
                  + "set {}=0 in application.properties",SLAVE_MODE_SLEEP_TIME);
            } else {
              log.info("If this is a Gerrit connecting to a SLAVE database configuration you should "
                  + "set {}=100 in application.properties",SLAVE_MODE_SLEEP_TIME);
            }
          } catch (Exception e) {
            log.warn(SLAVE_MODE_SLEEP_TIME+" property must be the number of milliseconds to wait for",e);
          }
        } catch(IOException e) {
          log.error("While reading GerritMS properties file",e);
        }
      }
    } catch(IOException ee) {
      log.error("While loading the .gitconfig file",ee);
    }
  }
}
