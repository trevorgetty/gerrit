package com.google.gerrit.server.replication;

/**
 * Constants used by the replication code....
 *  - whether it be to name a configuration value,
 *  - give a replication configuration a default value etc,
 *
 *  There are all defined here to enable us to see what we use and expose easier.
 */
public final class ReplicationConstants {

  /************************************
   * Replication configuration name
   */
  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_SYNC_FILES = "gerrit.replicated.events.enabled.sync.files";
  public static final String GERRIT_REPLICATED_EVENTS_BASEPATH = "gerrit.replicated.events.basepath";
  public static final String GERRIT_EVENT_BASEPATH = "gerrit.events.basepath";

  public static final String GERRIT_REPLICATED_EVENTS_INCOMING_ARE_GZIPPED = "gerrit.replicated.events.incoming.gzipped";
  public static final String GERRIT_MAX_MS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "gerrit.replicated.events.secs.before.proposing";
  public static final String GERRIT_CACHE_NAMES_NOT_TO_BE_RELOADED = "gerrit.replicated.cache.names.not.to.reload";
  public static final String GERRIT_MAX_EVENTS_TO_APPEND_BEFORE_PROPOSING = "gerrit.replicated.events.max.append.before.proposing";
  public static final String GERRIT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "gerrit.max.secs.to.wait.on.poll.and.read";
  public static final String GERRIT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME = "gerrit.replicated.index.unique.changes.queue.wait.time";
  public static final String GERRIT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "gerrit.minutes.since.change.last.indexed.check.period";
  public static final String GERRIT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME = "gerrit.index.events.ready.seconds.wait.time";

  //Events can be skipped by providing a comma seperated list of event types.
  //e.g, TopicChangedEvent, ReviewerDeletedEvent, ReviewerUpdatedEvent
  public static final String GERRIT_EVENT_TYPES_TO_BE_SKIPPED = "gerrit.event.types.disabled.list";

  public static final String GERRIT_REPLICATED_EVENTS_ENABLED_RECEIVE = "gerrit.replicated.events.enabled.receive";
  public static final String GERRIT_REPLICATED_EVENTS_RECEIVE_ORIGINAL = "gerrit.replicated.events.enabled.receive.original";
  public static final String GERRIT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "gerrit.replicated.events.secs.on.queue";

  public static final String DEFAULT_BYTE_ENCODING = "UTF-8"; // From BaseCommand
  public static final String DEFAULT_MAX_SECS_TO_WAIT_FOR_EVENT_ON_QUEUE = "5";

  public static final String EVENTS_REPLICATION_THREAD_NAME = "EventsStreamReplication";
  public static final String GERRIT_REPLICATION_THREAD_NAME = "ReplicatorStreamReplication";
  public static final String DEFAULT_MS_APPLICATION_PROPERTIES = "/opt/wandisco/git-multisite/replicator/properties/";

  /*****************************************************************************************
   * Debug centric configuration that allows specific override behaviour.
   * This is better than setting a constant and requiring a rebuild -
   * instead uses System.getProperty(x, System.getenv(x)).
   */
  public static final String GERRITMS_INTERNAL_LOGGING = "gerritms_internal_logging";
  public static final String REPLICATION_DISABLED = "gerritms_replication_disabled";

  /**********************************
   * Replicated event directories
   */
  public static final String INDEXING_DIR = "index_events";
  public static final String INCOMING_PERSISTED_DIR = "incoming-persisted";

  /***************************************
   * Default Configuration Values.
   */
  // as shown by statistics this means less than 2K gzipped proposals
  public static final String DEFAULT_MAX_EVENTS_PER_FILE = "30";

  //Default wait times if no configuration provided in application.properties
  public static final String DEFAULT_MAX_SECS_TO_WAIT_BEFORE_PROPOSING_EVENTS = "5";
  public static final String DEFAULT_MAX_SECS_TO_WAIT_ON_POLL_AND_READ = "1";
  public static final String DEFAULT_REPLICATED_INDEX_UNIQUE_CHANGES_QUEUE_WAIT_TIME = "20";
  public static final String DEFAULT_MINUTES_SINCE_CHANGE_LAST_INDEXED_CHECK_PERIOD = "60";
  public static final String DEFAULT_INDEX_EVENTS_READY_SECONDS_WAIT_TIME = "60";

  // Stats
  public static long DEFAULT_STATS_UPDATE_TIME = 20000L;
}
