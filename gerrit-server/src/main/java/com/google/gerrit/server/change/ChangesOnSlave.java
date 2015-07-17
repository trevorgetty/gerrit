package com.google.gerrit.server.change;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.SlaveWait;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gwtorm.server.OrmConcurrencyException;
import com.google.gwtorm.server.OrmException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLTransactionRollbackException;
import java.util.Collections;
import java.util.Properties;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes care of the master-master and master-slave configurations.
 * The master-master is presuming there is an InnoDB engine operating.
 * So if an exception is thrown due to a deadlock, it tries to retry.
 *
 * This can also be used for a master-slave configuration, with a mysql-proxy but with an InnoDB on the master.
 * 
 * For the master-slave creates a new row in the slave_waits table
 * (using the master DB) and then waits for that row to be created on the slave.
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
  public static final long TOTAL_TIME_TO_WAIT_ON_SLAVE = 5 * 60 * 1000L; // 5 minutes
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES="/opt/wandisco/git-multisite/replicator/properties/";
  public static final String SLAVE_MODE_SLEEP_TIME = "gerrit.db.slavemode.sleepTime";
  public static final String DEADLOCKS_RETRY_ON = "gerrit.db.mastermaster.retryOnDeadLocks";
  public static final String DEADLOCKS_ROLLBACK_TRANSACTION_RETRY_TIMES = "gerrit.db.mastermaster.rollbackRetryTimes";
  public static final String DEADLOCKS_ROLLBACK_TRANSACTION_RETRY_WAIT = "gerrit.db.mastermaster.rollbackRetryWaitMs";

  public static final boolean ROLLBACK_TRANSACTION_RETRY_DEFAULT = true;
  public static final int ROLLBACK_TRANSACTION_RETRY_TIMES_DEFAULT = 50;
  public static final long ROLLBACK_TRANSACTION_RETRY_WAIT_DEFAULT = 3000L;
  private static boolean retryOnDeadlocks = ROLLBACK_TRANSACTION_RETRY_DEFAULT; // used with Percona master-master configuration (or InnoDB)
  private static int rollbackTransactionRetryTimes = ROLLBACK_TRANSACTION_RETRY_TIMES_DEFAULT;
  private static long rollbackTransactionRetryWait = ROLLBACK_TRANSACTION_RETRY_WAIT_DEFAULT;
  private static long sleepTimeBetweenChecks = 0L; // used for mysql-proxy master-slave config. 0L on the master
  
  private static final Logger log = LoggerFactory.getLogger(ChangesOnSlave.class);
  public static final boolean DELETE_AFTER_RETRIEVAL_ON_SLAVE = true;
  
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
      while (timeSoFar <= TOTAL_TIME_TO_WAIT_ON_SLAVE) {
        timeSoFar += sleepTimeBetweenChecks;
        // let's try to get the row from the local slave DB
        SlaveWait slaveWait = db.slaveWaits().get(id);
        if (slaveWait != null) {
          log.debug("SlaveId {} found!", id);
          if (DELETE_AFTER_RETRIEVAL_ON_SLAVE) {
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
  
  public static boolean checkIfTransactionDeadlocksAndRollback(ReviewDb db,Throwable maybeSqlTransactionRollbackException,int retriedSoFar)
      throws OrmException {
    if (retriedSoFar >= ChangesOnSlave.rollbackTransactionRetryTimes) {
      log.warn("Too many attempts, giving up on SQLTransactionRollbackException {}",retriedSoFar);
      return false;
    }
    if (ChangesOnSlave.retryOnDeadlocks) {
      // Here we check if we are dying for a SQLTransactionRollbackException or a OrmConcurrencyException
      // In both cases we retry
      if (maybeSqlTransactionRollbackException instanceof OrmConcurrencyException) {
        String msg = "Got SQLRBE OrmConcurrencyException, attempt No: {}/{} ";
        Object[] parms = new Object[] {retriedSoFar, ChangesOnSlave.rollbackTransactionRetryTimes};
        if (retriedSoFar > 1) { // only clutter the log if we are taking some time
          log.error(msg,parms );
        } else {
          log.debug(msg,parms );
        }
        db.rollback();
        ChangesOnSlave.waitForTransactionRetry();
        return true;
      } else {
        Throwable cause = maybeSqlTransactionRollbackException;
        while (cause != null) {
          cause = cause.getCause();
          if (cause instanceof SQLTransactionRollbackException || cause instanceof OrmConcurrencyException) {
            String msg = "Got SQLRBE {}, attempt No: {}/{} ";
            Object[] parms = new Object[] {cause.getClass(),retriedSoFar, ChangesOnSlave.rollbackTransactionRetryTimes};
            if (retriedSoFar > 1) {
              log.error(msg, parms );
            } else {
              log.debug(msg, parms );
            }
            db.rollback();
            ChangesOnSlave.waitForTransactionRetry();
            return true;
          } else {
            if (retriedSoFar > 1) {
              log.info("Current exception is not SQLTransactionRollbackException: {}",(cause==null? "null":cause.getClass()));
            }
          }
        }
      }
      if (retriedSoFar > 1) {
        log.info("Out of deadlock checkpoint");
      } else {
        log.debug("Out of deadlock checkpoint");
      }
    }
    return false;
  }
  
  private static void waitForTransactionRetry() {
    try {
      log.debug("waitForTransactionRetry, WAITING for {}ms...",rollbackTransactionRetryWait);
      Thread.sleep(rollbackTransactionRetryWait);
    } catch (InterruptedException ex) {
      log.warn("While waiting on retry",ex);
    }
  }
  
  private static void readConfiguration() {
    try {
      String gitConfigLoc = System.getenv("GIT_CONFIG");
 
      if (gitConfigLoc == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }
      
      FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
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
        try (FileInputStream input = new FileInputStream(applicationProperties)) {
          Properties props = new Properties();
          props.load(input);
          //
          // proxy (slaves) zone
          //
          setProxyMasterSlave(props);
          //
          // Retry on deadlocks zone (master-master)
          //
          setMasterMaster(props);
        } catch(IOException e) {
          log.error("While reading GerritMS properties file",e);
        }
        if (sleepTimeBetweenChecks > 0 && retryOnDeadlocks) {
          log.warn("You have a master-slave and a master-master configuration at the same time!");
          log.warn("This could slow down writes on a master-master or a single master database.");
          log.warn("Or could be a master-slave configuration with connection pooling to an InnoDB engine.");
          log.warn("See {} and {} in application.properties",new Object[] {DEADLOCKS_RETRY_ON,SLAVE_MODE_SLEEP_TIME});
        } else if (sleepTimeBetweenChecks > 0) {
          log.info("If this is a Gerrit connecting to a single or MASTER database configuration you should "
              + "set {}=0 in application.properties",SLAVE_MODE_SLEEP_TIME);
        } else if (!retryOnDeadlocks) {
          log.info("If this is a Gerrit connecting to a SLAVE database configuration you should "
              + "set {}=100 in application.properties",SLAVE_MODE_SLEEP_TIME);
        } else if (retryOnDeadlocks) {
          log.info("This Gerrit is connecting to a database with a master-master configuration. See {}",DEADLOCKS_RETRY_ON);
        }
      }
    } catch(IOException ee) {
      log.error("While loading the .gitconfig file",ee);
    }
  }

  /**
   * Reads properties for master-master configuration (Percona)
   * 
   * @param props 
   */
  private static void setMasterMaster(Properties props) {
      String retryOnDeadlocksStr = props.getProperty(DEADLOCKS_RETRY_ON,""+ROLLBACK_TRANSACTION_RETRY_DEFAULT);
      String rollbackTransactionRetryTimesStr = props.getProperty(DEADLOCKS_ROLLBACK_TRANSACTION_RETRY_TIMES,
              ""+ROLLBACK_TRANSACTION_RETRY_TIMES_DEFAULT);
      String rollbackTransactionRetryWaitStr = props.getProperty(DEADLOCKS_ROLLBACK_TRANSACTION_RETRY_WAIT,
              ""+ROLLBACK_TRANSACTION_RETRY_WAIT_DEFAULT);
      try {
          retryOnDeadlocks = Boolean.parseBoolean(retryOnDeadlocksStr);
          rollbackTransactionRetryTimes = Integer.parseInt(rollbackTransactionRetryTimesStr);
          rollbackTransactionRetryWait = Integer.parseInt(rollbackTransactionRetryWaitStr);
      } catch(Exception e) {
          log.warn("Setting {} properties as default",DEADLOCKS_RETRY_ON,e);
          retryOnDeadlocks = ROLLBACK_TRANSACTION_RETRY_DEFAULT;
          rollbackTransactionRetryTimes = ROLLBACK_TRANSACTION_RETRY_TIMES_DEFAULT;
          rollbackTransactionRetryWait = ROLLBACK_TRANSACTION_RETRY_WAIT_DEFAULT;
      }
      log.info("master-master {}={}",new Object[] {DEADLOCKS_RETRY_ON,retryOnDeadlocks});
  }

  /**
   * Reads properties for master-slave configuration (mysql-proxy)
   * 
   * @param props 
   */
  private static void setProxyMasterSlave(Properties props) {
      String sleepTimeStr = props.getProperty(SLAVE_MODE_SLEEP_TIME,""+sleepTimeBetweenChecks);
      try {
          sleepTimeBetweenChecks = Integer.parseInt(sleepTimeStr);
          if (sleepTimeBetweenChecks > 0 && sleepTimeBetweenChecks < 10) {
              sleepTimeBetweenChecks = 10; // min time to wait
          }
          log.info("{}={}",new Object[] {SLAVE_MODE_SLEEP_TIME,sleepTimeBetweenChecks});
      } catch (Exception e) {
          log.warn(SLAVE_MODE_SLEEP_TIME+" property must be the number of milliseconds to wait for",e);
      }
  }
}
