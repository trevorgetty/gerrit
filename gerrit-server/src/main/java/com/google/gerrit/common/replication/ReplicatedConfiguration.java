package com.google.gerrit.common.replication;

import com.google.common.base.Strings;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_BASE_DIR;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_GERRIT_MAX_NUM_EVENTS_RETRIES;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MAX_EVENTS_PER_FILE;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_MS_APPLICATION_PROPERTIES;
import static com.google.gerrit.common.replication.ReplicationConstants.FAILED_DIR;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENTS_BACKOFF_CEILING_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENT_BASEPATH;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_EVENT_TYPES_TO_BE_SKIPPED;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_LOGGING_PERIOD_SECS;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_NUM_EVENTS_RETRIES;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_BASEPATH;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_IDLE_TIME_SECS;
import static com.google.gerrit.common.replication.ReplicationConstants.GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE;
import static com.google.gerrit.common.replication.ReplicationConstants.INCOMING_DIR;
import static com.google.gerrit.common.replication.ReplicationConstants.OUTGOING_DIR;
import static com.google.gerrit.common.replication.ReplicationConstants.REPLICATED_EVENTS_DIRECTORY_NAME;

/**
 * really we want to register this as a singleton in the guice bindings and let it be auto injected into any
 * class that needs it. but for now I am adding a getConfig call to the replicator main instance which can be used
 * by the others - and simply updated with injection later.
 */
@Singleton
public class ReplicatedConfiguration {

  /**
   * In the Daemon {@link com.google.gerrit.pgm.Daemon#createCfgInjector()} () ConfigInjector} this module is used as a child module to
   * bind this class with the cfgInjector. Later on in the {@link com.google.gerrit.pgm.Daemon#createSysInjector()} () ConfigInjector}
   * method we can then get an instance of this class from the cfgInjector to perform a check
   * whether replication is enabled or not.
   **/
  public static class Module extends LifecycleModule {
    @Override
    protected void configure() {
      bind(ReplicatedConfiguration.class);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ReplicatedConfiguration.class);

  private String thisNodeIdentity = null;

  /*******************************************************************
   * Replicated events configuration
   */
  private boolean replicatedEventsReceive = true;
  private boolean replicatedEventsReplicateOriginalEvents = true;
  private boolean receiveReplicatedEventsEnabled = true;
  private boolean replicatedEventsEnabled = true;
  private boolean replicatedEventsSend = true;
  private boolean localRepublishEnabled = false;
  private boolean incomingEventsAreGZipped = false; // on the landing node, is events file to be unzipped.
  private int maxNumberOfEventsBeforeProposing;
  private File replicatedEventsBaseDirectory = null;
  private File outgoingReplEventsDirectory = null;
  private File incomingReplEventsDirectory = null;
  private File incomingFailedReplEventsDirectory = null;
  private List<String> eventSkipList = new ArrayList<>(); //Events that we skip replication for

  /*******************************************************************
   * Thread / workers wait times
   * Wait time variables
   */
  private long maxSecsToWaitBeforeProposingEvents;
  private long eventWorkerDelayPeriodMs;
  private long minutesSinceChangeLastIndexedCheckPeriod;
  // New replicated event worker pool items.
  private int maxNumberOfEventWorkerThreads;
  private int maxIdlePeriodEventWorkerThreadInSeconds;

  private int indexMaxNumberBackoffRetries; // max number of backoff retries before failing an event group(file).
  private long indexBackoffInitialPeriodMs; // back off initial period that we start backoff doubling from per retry.
  private long indexBackoffCeilingPeriodMs; // max period in time of backoff doubling before, wait stays at this ceiling
  private LinkedList<Long> indexBackoffPeriods; // sequence of backoff periods in increasing value.

  private int loggingMaxPeriodValueMs;// get the value for logging something atMaxEvery Y ms.

  private Set<String> coreProjects = new TreeSet<String>();

  // replicated cache config
  private final ArrayList<String> cacheNamesNotToReload = new ArrayList<>();

  private boolean syncFiles = false;
  private String defaultBaseDir;
  private AllUsersNameProvider allUsersNameProvider;
  private AllProjectsNameProvider allProjectsNameProvider;
  private Config gerritServerConfig;
  private ConfigureReplication configureReplication;

  /**
   * Construct this singleton class, and read the configuration.. Only thing forcing singleton
   * at moment is that its only constructed within our static replicator instance, but we will move
   * to proper singleton injected bindings later!
   *
   * @throws ConfigInvalidException
   */
  @Inject
  public ReplicatedConfiguration(@GerritServerConfig Config config,
                                 AllProjectsNameProvider allProjectsNameProvider,
                                 AllUsersNameProvider allUsersNameProvider) throws ConfigInvalidException {

    this.gerritServerConfig = config;
    this.allProjectsNameProvider = allProjectsNameProvider;
    this.allUsersNameProvider = allUsersNameProvider;
    this.configureReplication = ConfigureReplication.getInstance(config);

    // we record if a failure happened when reading the configuration - we only read config
    // on startup of our singleton now - prevents any locking issues or required checking - also
    // we now throw if something wrong and stop the service starting!!
    readConfiguration(null);
  }

  /**
   * Get GerritServerConfig
   * @return Instance of the Gerrit Server Configuration - N.B. not the replication configuration.
   */
  public Config getGerritServerConfig() {
    return gerritServerConfig;
  }

  /**
   * Get the instance of the ConfigureReplication which determines if Gerrit
   * replication is disabled or not. If disabled Gerrit will run in vanilla.
   *
   * @return instance of ConfigureReplication which can be used to determine
   * if replication is disabled or not
   */
  public ConfigureReplication getConfigureReplication() {
    return configureReplication;
  }

  public int getMaxNumberOfEventWorkerThreads() {
    return maxNumberOfEventWorkerThreads;
  }

  /**
   * Returns the total number of core worker threads, without the core threads affecting the count so we can work out
   * if we can schedule normal project event files.
   */
  public int getNumberOfNonCoreWorkerThreads() {
    return maxNumberOfEventWorkerThreads - coreProjects.size();
  }

  public int getMaxIdlePeriodEventWorkerThreadInSeconds() {
    return maxIdlePeriodEventWorkerThreadInSeconds;
  }

  public Set<String> getCoreProjects() {
    return coreProjects;
  }

  public boolean isReplicatedEventsReceive() {
    return replicatedEventsReceive;
  }

  public boolean isReplicatedEventsReplicateOriginalEvents() {
    return replicatedEventsReplicateOriginalEvents;
  }

  public boolean isReceiveReplicatedEventsEnabled() {
    return receiveReplicatedEventsEnabled;
  }

  public boolean isReplicatedEventsEnabled() {
    return replicatedEventsEnabled;
  }

  public boolean isReplicatedEventsSend() {
    return replicatedEventsSend;
  }

  public boolean isLocalRepublishEnabled() {
    return localRepublishEnabled;
  }

  public int getMaxIndexBackoffRetries() {
    return indexMaxNumberBackoffRetries;
  }

  public long getIndexBackoffInitialPeriodMs() {
    return indexBackoffInitialPeriodMs;
  }

  public long getIndexBackoffCeilingPeriodMs() {
    return indexBackoffCeilingPeriodMs;
  }

  public List<String> getEventSkipList() {
    return eventSkipList;
  }

  /**
   * index into our sequence of backoff periods to get the length of time a given failure should
   * be held off.
   *
   * @return A long value for the backoff period which represents the length of time a
   * given failure should be held off
   */
  public long getIndexBackoffPeriodMs(int numAttemptedRetries) throws IndexOutOfBoundsException {
    // we have calculated this up front for simplicity of working it out, and speedy access now.
    if (numAttemptedRetries <= 0) {
      throw new IndexOutOfBoundsException("Invalid index - param is 1 based matching our retry counter!");
    }
    if (numAttemptedRetries > indexMaxNumberBackoffRetries) {
      throw new IndexOutOfBoundsException(
          String.format("Requesting number: %s when max number of requested retries is %s.",
              numAttemptedRetries, indexMaxNumberBackoffRetries));
    }
    return indexBackoffPeriods.get(numAttemptedRetries - 1);
  }

  public int getLoggingMaxPeriodValueMs() {
    return loggingMaxPeriodValueMs;
  }

  /**
   * construction used by testing to allow supplying of properties for controlling real behaviour
   * in classes without requiring mocks.
   *
   * @param testingProperties
   */
  public ReplicatedConfiguration(Properties testingProperties) throws ConfigInvalidException {
    readConfiguration(testingProperties);
  }


  /**
   * Finds the location of the application.properties on the system if no properties are supplied directly
   * to the method. Properies are supplied directly during testing.
   * <p>
   * Only called on startup - no real downside to coarse locking - no multi threading performance worries.
   *
   * @param suppliedProperties : Supplied properties to be passed to the method during testing.
   * @throws ConfigInvalidException
   */
  private void readConfiguration(Properties suppliedProperties) throws ConfigInvalidException {

    try {
      if (suppliedProperties == null && configureReplication.isReplicationDisabled()) {
        return;
      }

      if (suppliedProperties != null) {
        // we allow supplied properties to be given to us so we can dummy config information
        // on the fly for tests easily without complicated mocking.
        log.info("Setting up replicated configuration with supplied properties.");

        readAndDefaultConfigurationFromProperties(suppliedProperties);
        //Required for tests
        replicatedEventsBaseDirectory = new File(defaultBaseDir);
        outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory, OUTGOING_DIR);
        incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR);
        incomingFailedReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR + File.separator + FAILED_DIR);
        return;
      }

      // Used for internal integration tests at WANdisco
      String gitConfigLoc =
          System.getProperty("GIT_CONFIG", System.getenv("GIT_CONFIG"));
      if (Strings.isNullOrEmpty(gitConfigLoc)
          && System.getenv("GIT_CONFIG") == null) {
        gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
      }

      FileBasedConfig config =
          new FileBasedConfig(new File(gitConfigLoc), FS.DETECTED);
      try {
        config.load();
      } catch (ConfigInvalidException e) {
        // Configuration file is not in the valid format, throw exception back.
        throw new IOException(e);
      }

      File applicationProperties;
      try {
        String appProperties = config.getString("core", null, "gitmsconfig");
        applicationProperties = new File(appProperties);
        // GER-662 NPE thrown if GerritMS is started without a reference to a
        // valid GitMS application.properties file.
      } catch (NullPointerException exception) {
        throw new FileNotFoundException(
            "GerritMS cannot continue without a valid GitMS application.properties file referenced in its .gitconfig file.");
      }

      if (!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (1) " + applicationProperties);
        applicationProperties = new File(DEFAULT_MS_APPLICATION_PROPERTIES,
            "application.properties");
      }

      if (!applicationProperties.exists() || !applicationProperties.canRead()) {
        log.warn("Could not find/read (2) " + applicationProperties);
        defaultBaseDir = DEFAULT_BASE_DIR + File.separator
            + REPLICATED_EVENTS_DIRECTORY_NAME;
      } else {
        Properties props = new Properties();
        try (FileInputStream propsFile =
                 new FileInputStream(applicationProperties)) {
          props.load(propsFile);
          readAndDefaultConfigurationFromProperties(props);
        } catch (IOException e) {
          // we cant continue with invalid properties file - throw!
          log.error("While reading GerritMS properties file", e);
          throw new ConfigInvalidException("Unable to continue with invalid GerritMS replicated properties file: " + applicationProperties.getAbsolutePath());
        }
      }

      replicatedEventsBaseDirectory = new File(defaultBaseDir);
      outgoingReplEventsDirectory = new File(replicatedEventsBaseDirectory, OUTGOING_DIR);
      incomingReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR);
      incomingFailedReplEventsDirectory = new File(replicatedEventsBaseDirectory, INCOMING_DIR + File.separator + FAILED_DIR);

    } catch (IOException ee) {
      log.error("While loading the .gitconfig file", ee);
      throw new ConfigInvalidException("Unable to continue without valid GerritMS configuration.");
    }
  }


  /**
   * Reads the properties instance given and uses default values for setting the properties if
   * the properties are not set as part of the Properties instance.
   *
   * @param props : An Properties instance representing properties set via the GitMS application.properties
   *              or supplied properties given during testing.
   */
  private void readAndDefaultConfigurationFromProperties(Properties props) {
    syncFiles = Boolean.parseBoolean(props.getProperty(
        GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES, "false"));

    // The user can set a different path specific for the replicated
    // events. If it's not there
    // then the usual GERRIT_EVENT_BASEPATH will be taken.
    defaultBaseDir = props.getProperty(GERRIT_REPLICATED_EVENTS_BASEPATH);
    if (defaultBaseDir == null) {
      defaultBaseDir = props.getProperty(GERRIT_EVENT_BASEPATH);
      if (defaultBaseDir == null) {
        defaultBaseDir = DEFAULT_BASE_DIR;
      }
      defaultBaseDir += File.separator + REPLICATED_EVENTS_DIRECTORY_NAME;
    }

    incomingEventsAreGZipped = Boolean.parseBoolean(props.getProperty(
        GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED, "false"));

    // Getting the node identity that will be used to determine the
    // originating node for each instance.
    thisNodeIdentity = props.getProperty("node.id");

    // Configurable for the maximum amount of events allowed in the
    // outgoing events file before proposing.
    maxNumberOfEventsBeforeProposing = Integer.parseInt(removeLFromLong(
        props.getProperty(GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING,
            DEFAULT_MAX_EVENTS_PER_FILE)));

    // Configurable for the maximum amount of seconds to wait before
    // proposing events in the outgoing events file.
    maxSecsToWaitBeforeProposingEvents =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS,
                DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS)));

    // Configurable for the wait time for threads waiting on an event to
    // be received and published.
    eventWorkerDelayPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(
            props.getProperty(GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ,
                DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ)));

    // max number of backoff retries before failing an event group(file).
    indexMaxNumberBackoffRetries =
        Math.max(1, Integer.parseInt(removeLFromLong(
            props.getProperty(GERRIT_MAX_NUM_EVENTS_RETRIES,
                DEFAULT_GERRIT_MAX_NUM_EVENTS_RETRIES))));

    // back off initial period that we start backoff doubling from per retry
    indexBackoffInitialPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD,
                DEFAULT_GERRIT_EVENTS_BACKOFF_INITIAL_PERIOD)));

    // max period in time of backoff doubling before, wait stays at this ceiling
    indexBackoffCeilingPeriodMs =
        Long.parseLong(sanitizeLongValueAndConvertToMilliseconds(props
            .getProperty(GERRIT_EVENTS_BACKOFF_CEILING_PERIOD,
                DEFAULT_GERRIT_EVENTS_BACKOFF_CEILING_PERIOD)));


    // Read in a comma separated list of events that should be skipped. Arrays.asList returns
    // a fixed size list and cannot be mutated so using an ArrayList here instead that takes a default list with an
    // initial size in order to later on have the ability to use addAll() to join the default list
    // with the events in property file list.
    final List<String> defaultTypesToSkip
        = new ArrayList<>(Arrays.asList("RefReplicatedEvent", "RefReplicationDoneEvent"));
    eventSkipList = getPropertyAsList(props, GERRIT_EVENT_TYPES_TO_BE_SKIPPED, defaultTypesToSkip);
    //Setting all to lowercase so user doesn't have to worry about correct casing.
    replaceAllAsLowerCase(eventSkipList);

    // Now we have the index backoff information - lets calculate the sequence of backoffs.
    indexBackoffPeriods = new LinkedList<>();

    for (int index = 1; index <= indexMaxNumberBackoffRetries; index++) {
      indexBackoffPeriods.add(Math.min(indexBackoffCeilingPeriodMs, (long) (indexBackoffInitialPeriodMs * (Math.pow(2, index - 1)))));
    }

    // log out the backoff periods
    log.info("Replicated Event failure backoff periods: {}", indexBackoffPeriods.toString());

    // get the value for logging something atMaxEvery Y Timeunit.  Note we take the value in seconds,
    // but we allow the use of 0.5 etc in our props file.  We convert to MS then for each of knowing which timeunit
    // to use.
    loggingMaxPeriodValueMs = Integer.parseInt(sanitizeLongValueAndConvertToMilliseconds(
        props.getProperty(GERRIT_MAX_LOGGING_PERIOD_SECS, DEFAULT_MAX_LOGGING_PERIOD_VALUE_SECS)));


    // Configurable for the time period to check since the change was last
    // indexed, The change will need reindexed
    // if it has been in the queue more than the specified check period.
    // Default is 1 hour.
    minutesSinceChangeLastIndexedCheckPeriod =
        TimeUnit.MINUTES.toMillis(Long.parseLong(props.getProperty(
            GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD,
            DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD)));


    log.info("Property {}={}", GERRIT_REPLICATED_EVENTS_BASEPATH, defaultBaseDir);

    // Replicated CACHE properties
    try {
      String[] tempCacheNames =
          props.getProperty(GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED,
              "invalid_cache_name").split(",");
      for (String s : tempCacheNames) {
        String st = s.trim();
        if (st.length() > 0) {
          cacheNamesNotToReload.add(st);
        }
      }
    } catch (Exception e) {
      // we can continue with some defaults - just record this problem.
      log.error("Not able to load cache properties", e);
    }

    replicatedEventsSend = true; // they must be always enabled, not dependant on GERRIT_REPLICATED_EVENTS_ENABLED_SEND
    replicatedEventsReceive = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE, "true"));
    replicatedEventsReplicateOriginalEvents = Boolean.parseBoolean(props.getProperty(GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL, "true"));

    receiveReplicatedEventsEnabled = replicatedEventsReceive || replicatedEventsReplicateOriginalEvents;
    replicatedEventsEnabled = receiveReplicatedEventsEnabled || replicatedEventsSend;
    if (replicatedEventsEnabled) {
      log.info("RE Replicated events are enabled, send: {}, receive: {}", replicatedEventsSend, receiveReplicatedEventsEnabled);

      int workerBaseNumThreads = Integer.parseInt(
          props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_POOL_SIZE, DEFAULT_EVENT_WORKER_POOL_SIZE));

      if ( workerBaseNumThreads < 1 ){
        log.error("Invalid number of worker threads indicated which is less than 1 - indicating default number {}", DEFAULT_EVENT_WORKER_POOL_SIZE);
        workerBaseNumThreads = Integer.parseInt(DEFAULT_EVENT_WORKER_POOL_SIZE);
      }

      // Now add on the amount of core threads, so that the max number of threads is the max thread pool size
      // get the real name for special projects.
      coreProjects.add(getAllProjectsName());
      coreProjects.add(getAllUsersName());

      // Total number is the core thread pool size, plus our worker size.
      maxNumberOfEventWorkerThreads = workerBaseNumThreads + coreProjects.size();

      // Default event worker idle period is 60X5 for 5mins.  Dont want a heavy thread churn.
      maxIdlePeriodEventWorkerThreadInSeconds = Integer.parseInt(
          props.getProperty(GERRIT_REPLICATED_EVENT_WORKER_POOL_IDLE_TIME_SECS, "300"));

      log.info("RE Replicated events are to be processed using worker pool size: {} maxIdlePeriodSecs: {}.",
          maxNumberOfEventWorkerThreads, maxIdlePeriodEventWorkerThreadInSeconds);
    } else {
      log.info("RE Replicated events are disabled"); // This could not apppear in the log... cause the log could not yet be ready
    }

    log.info("RE Replicated events: receive={}, original={}, send={} ",
        replicatedEventsReceive, replicatedEventsReplicateOriginalEvents, replicatedEventsSend);
  }

  /**
   * Get the dynamic name for All-Projects
   *
   * @return String value of the name of the All-Projects core project
   */
  public String getAllProjectsName() {
    return allProjectsNameProvider != null ? allProjectsNameProvider.get().get() : AllProjectsNameProvider.DEFAULT;
  }

  /**
   * Get the dynamic name for All-Users
   *
   * @return String value of the name of the All-Users core project
   */
  public String getAllUsersName() {
    return allUsersNameProvider != null ? allUsersNameProvider.get().get() : AllUsersNameProvider.DEFAULT;
  }

  public String getThisNodeIdentity() {
    return thisNodeIdentity;
  }

  public int getMaxNumberOfEventsBeforeProposing() {
    return maxNumberOfEventsBeforeProposing;
  }

  public long getMaxSecsToWaitBeforeProposingEvents() {
    return maxSecsToWaitBeforeProposingEvents;
  }

  public long getEventWorkerDelayPeriodMs() {
    return eventWorkerDelayPeriodMs;
  }

  public File getReplicatedEventsBaseDirectory() {
    return replicatedEventsBaseDirectory;
  }

  public File getOutgoingReplEventsDirectory() {
    return outgoingReplEventsDirectory;
  }

  public File getIncomingReplEventsDirectory() {
    return incomingReplEventsDirectory;
  }

  public File getIncomingFailedReplEventsDirectory() {
    return incomingFailedReplEventsDirectory;
  }

  public boolean isIncomingEventsAreGZipped() {
    return incomingEventsAreGZipped;
  }

  public ArrayList<String> getCacheNamesNotToReload() {
    return cacheNamesNotToReload;
  }

  public boolean isSyncFiles() {
    return syncFiles;
  }

  public String getDefaultBaseDir() {
    return defaultBaseDir;
  }

  public final boolean isCacheToBeReloaded(String cacheName) {
    return !cacheNamesNotToReload.contains(cacheName);
  }

  /**
   * Returns the number of minutes since the change was last indexed
   *
   * @return Returns number of Minutes since the last Indexed check period.
   */
  public long getMinutesSinceChangeLastIndexedCheckPeriod() {
    return minutesSinceChangeLastIndexedCheckPeriod;
  }

  private static String removeLFromLong(String property) {
    if (property != null && property.length() > 1 && (property.endsWith("L") || property.endsWith("l"))) {
      return property.substring(0, property.length() - 1);
    }
    return property;
  }


  private static String convertToMs(final String property){
    return Long.toString(Long.parseLong(property) * 1000);
  }

  /**
   * Using milliseconds so that the user can specify sub second periods
   *
   * @param property the string value taken from the properties file
   * @return the string value in milliseconds
   */
  public static String sanitizeLongValueAndConvertToMilliseconds(final String property) {
    //String the L or l off the property value, e.g 5L -> 5
    final String sanitizedProp = removeLFromLong(property);

    if (sanitizedProp.contains(".")) {
      double x = Double.parseDouble(sanitizedProp) * 1000;
      long y = (long) x;
      return Long.toString(y);
    }
    //With the L or l stripped off, we need to convert to milliseconds now, e.g 5 -> 5000
    return convertToMs(sanitizedProp);
  }


  /**
   * Version of getProperty that returns a property that contains a comma separated
   * list of values as a list.
   * e.g property=value1, value2, value3
   * will return a List with value1, value2, value3 as entries in the list.
   * @param propertyName This is the name in the GitMS application.properties to look for.
   * @return A list with all the event types to be skipped.
   */
  public List<String> getPropertyAsList(final Properties properties, final String propertyName,
                                        final List<String> defaultEventsTypesToSkip) {
    //The call to getProperty will return a single string which could contain multiple values
    //that need to be split into individual values.
    final String strValue = properties.getProperty(propertyName);
    if(StringUtils.isEmptyOrNull(strValue)){
      return new ArrayList<>(defaultEventsTypesToSkip);
    }
    List<String> propFileEventsToSkip = Arrays.asList(strValue.split("\\s*,\\s*"));
    if ( propFileEventsToSkip.isEmpty() ){
      return defaultEventsTypesToSkip;
    }
    return propFileEventsToSkip;
  }

  //Utility method to replace all in a list of strings with lowercase string
  public static void replaceAllAsLowerCase(List<String> eventsToBeSkipped) {
    ListIterator<String> iterator = eventsToBeSkipped.listIterator();
    while (iterator.hasNext()) {
      iterator.set(iterator.next().toLowerCase());
    }
  }


  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ReplicatedConfiguration{");
    sb.append("replicationDisabled=").append(configureReplication.isReplicationDisabled());
    sb.append(", thisNodeIdentity='").append(getThisNodeIdentity()).append('\'');
    sb.append(", maxNumberOfEventsBeforeProposing=").append(getMaxNumberOfEventsBeforeProposing());
    sb.append(", maxSecsToWaitBeforeProposingEvents=").append(getMaxSecsToWaitBeforeProposingEvents());
    sb.append(", eventWorkerDelayPeriodMs=").append(getEventWorkerDelayPeriodMs());
    sb.append(", minutesSinceChangeLastIndexedCheckPeriod=").append(getMinutesSinceChangeLastIndexedCheckPeriod());
    sb.append(", indexMaxNumberBackoffRetries=").append(getMaxIndexBackoffRetries());
    sb.append(", indexBackoffInitialPeriodMs=").append(getIndexBackoffInitialPeriodMs());
    sb.append(", indexBackoffCeilingPeriodMs=").append(getIndexBackoffCeilingPeriodMs());
    sb.append(", indexBackoffPeriods=").append(indexBackoffPeriods);
    sb.append(", replicatedEventsBaseDirectory=").append(getReplicatedEventsBaseDirectory());
    sb.append(", outgoingReplEventsDirectory=").append(getOutgoingReplEventsDirectory());
    sb.append(", incomingReplEventsDirectory=").append(getIncomingReplEventsDirectory());
    sb.append(", incomingFailedReplEventsDirectory=").append(getIncomingFailedReplEventsDirectory());
    sb.append(", replicatedEventsReceive=").append(replicatedEventsReceive);
    sb.append(", replicatedEventsReplicateOriginalEvents=").append(replicatedEventsReplicateOriginalEvents);
    sb.append(", receiveReplicatedEventsEnabled=").append(receiveReplicatedEventsEnabled);
    sb.append(", replicatedEventsEnabled=").append(replicatedEventsEnabled);
    sb.append(", replicatedEventsSend=").append(replicatedEventsSend);
    sb.append(", localRepublishEnabled=").append(localRepublishEnabled);
    sb.append(", incomingEventsAreGZipped=").append(incomingEventsAreGZipped);
    sb.append(", maxNumberOfEventWorkerThreads=").append(getMaxNumberOfEventWorkerThreads());
    sb.append(", maxIdlePeriodEventWorkerThreadInSeconds=").append(getMaxIdlePeriodEventWorkerThreadInSeconds());
    sb.append(", coreProjects=").append(getCoreProjects());
    sb.append(", cacheNamesNotToReload=").append(getCacheNamesNotToReload());
    sb.append('}');
    return sb.toString();
  }
}
