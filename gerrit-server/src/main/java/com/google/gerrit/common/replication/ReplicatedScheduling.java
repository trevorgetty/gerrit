package com.google.gerrit.common.replication;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.security.InvalidParameterException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for scheduling the work which is to be completed for events.
 * It is responsible for monitoring the core threads which are reserved along with sending
 * replicated tasks out to the thread pool for completion by any thread in the pool.
 */
@Singleton
public class ReplicatedScheduling {

  private static final Logger log = LoggerFactory.getLogger(ReplicatedScheduling.class);
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ConcurrentHashMap<String, ReplicatedEventTask> eventsFilesInProgress; // map of project to event file of work in progress.
  private final HashMap<String, Deque<File>> skippedProjectsEventFiles; // map of project to event file of work in progress.
  /**
   * list of projects that we are to stop processing for a period of time.
   * We do this -> backoff period of time by recording when we added the project(failed the project event)
   * And a max number of failures - see ProjectBackoffPeriod for more information.
   * <p>
   * Note: uses configuration
   * indexMaxNumberBackoffRetries // max number of backoff retries before failing an event group(file).
   * indexBackoffInitialPeriod // back off initial period that we start backoff doubling from per retry.
   * indexBackoffCeilingPeriod // max period in time of backoff doubling before, wait stays at this ceiling
   */
  private final ConcurrentHashMap<String, ProjectBackoffPeriod> skipProcessingAndBackoffThisProjectForNow;
  private final ReplicatedThreadPoolExecutor replicatedWorkThreadPoolExecutor;
  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  private final ReplicatedConfiguration replicatedConfiguration;
  private ScheduledFuture<?> scheduledIncomingFuture;
  private ScheduledFuture<?> scheduledOutgoingFuture;
  private ScheduledThreadPoolExecutor workerPool;

  private boolean allEventsFilesHaveBeenProcessedSuccessfully = false;
  private long eventDirLastModifiedTime = 0;

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   *
   * @param replicatedEventsCoordinator
   */
  public ReplicatedScheduling(ReplicatedEventsCoordinator replicatedEventsCoordinator) {

    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();

    // we keep a ConcurrentHashMap of the projects which are having events being processed on them, to avoid us having
    // multiple events files for the same project being operated on in parallel - this is quicker than looping
    // the actual thread pool and checking which tasks are in existence. Work in progress event files are added to the
    // map whenever scheduleReplicatedEventsTask() is called successfully. Entries are deleted from the map whenever
    // the event file processing has completed successfully or in error scenarios where the DB is out of date or processing
    // on the event file failed.
    eventsFilesInProgress = new ConcurrentHashMap<>(replicatedEventsCoordinator
        .getReplicatedConfiguration().getMaxNumberOfEventWorkerThreads());

    // We keep a LinkedHashMap of the skipped project events files to allow the following.
    // project A - event 1
    // project A - event 2
    // project B - event 1
    // We need to preserve the ordering of projectA lookups being event1, then event2.
    // we do not order the MAP by project in any way so we can use a standard hashmap for fast lookup of the
    // list/queue used per project stored internally.
    skippedProjectsEventFiles = new HashMap<>();

    // Allow us to skip processing of any project when we hit particular error cases - like DB stale, we back off processing
    // all further events for this project for now, until we finish this iteration.
    skipProcessingAndBackoffThisProjectForNow = new ConcurrentHashMap<>();

    /* Creating a new ScheduledThreadPoolExecutor instance with the given pool size of 2 (one for each worker).
     It is a fixed-sized Thread Pool so once the corePoolSize is given, you can not increase the
     size of the Thread Pool.*/
    workerPool = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2,
        new ReplicatedThreadFactory("Replicated-Incoming-Outgoing-worker-pool"));

    // Note the maxNumberOfEventWorkerThreads member is the configuration value, plus the number of reserved core
    // threads. e.g. maxNumber element says 10 and core projects has 2, then maxNumberOfEventWorkerThreads = 12.
    // The idea being that we always keep All-Projects and others processing ahead of other projects, as they have
    // important change schema in them that would prevent other projects working correctly.
    replicatedWorkThreadPoolExecutor = new ReplicatedThreadPoolExecutor(replicatedEventsCoordinator);
    SingletonEnforcement.registerClass(ReplicatedScheduling.class);
  }

  /**
   * This starts the ReplicatedIncomingEventWorker and ReplicatedOutgoingEventWorker threads
   * <p>
   * Scheduling the incomingEventWorker & outgoingEventWorker to execute after an initial period of 500ms
   * and then after that repeats periodically with a delay period specified by getEventWorkerDelayPeriod()
   * <p>
   * N.B :  For the scheduleWithFixedDelay, the period is interpreted as the delay between the end of the previous execution,
   * until the start of the next. The delay is then between finished executions, not between the beginning of executions.
   * This will give the workers the appropriate delay time between finished executions of either
   * pollAndWriteOutgoingEvents or readAndPublishIncomingEvents
   */
  public void startScheduledWorkers() {
    log.info("Scheduled ReplicatedIncomingEventWorker to run at fixed rate of every {} ms with initial delay of {} ms",
        replicatedConfiguration.getEventWorkerDelayPeriodMs(), replicatedConfiguration.getEventWorkerDelayPeriodMs());
    scheduledIncomingFuture =
        workerPool.scheduleWithFixedDelay(replicatedEventsCoordinator.getReplicatedIncomingEventWorker(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            TimeUnit.MILLISECONDS);

    log.info("Scheduled ReplicatedOutgoingEventWorker to run at fixed rate of every {} ms with initial delay of {} ms",
        replicatedConfiguration.getEventWorkerDelayPeriodMs(), replicatedConfiguration.getEventWorkerDelayPeriodMs());
    scheduledOutgoingFuture =
        workerPool.scheduleWithFixedDelay(replicatedEventsCoordinator.getReplicatedOutgoingEventWorker(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            replicatedConfiguration.getEventWorkerDelayPeriodMs(),
            TimeUnit.MILLISECONDS);
  }


  public void stop() {
    log.info("Shutting down scheduled thread pool for incoming / outgoing workers");

    // Null checking is being performed here as these scheduledFuture instances are only
    // created upon a lifecycle start. This lifecycle start will not be available in testing however
    // we still have the ability in tests to shutdown the worker pools as they are created upon construction.
    if (scheduledIncomingFuture != null && scheduledOutgoingFuture != null) {
      // Calling cancel on these scheduledFuture instances will attempt to cancel execution of these tasks.
      // This attempt will fail if the tasks have already completed,
      // have already been cancelled, or could not be cancelled for some other reason.
      // Setting boolean true if the thread executing these tasks should be interrupted;
      // otherwise, in-progress tasks are allowed to complete
      scheduledIncomingFuture.cancel(true);
      scheduledOutgoingFuture.cancel(true);
    }
    // Shutting down the worker thread pool, the ScheduledExecutorService needs to be shut down
    // when we are finished using it. If not, it will keep the JVM running,
    // even when all other threads have been shut down.
    // N.B These do not need to be null checked as they are created only upon construction.
    workerPool.shutdownNow();
    // quickly on shutdown log what is happening.
    log.info("Logging current state of the ReplicatedThreadPool on shutdown: {}", replicatedWorkThreadPoolExecutor.toString());
    replicatedWorkThreadPoolExecutor.shutdownNow();
    //We don't need an awaitTermination here as we don't have anything else to do.
    SingletonEnforcement.unregisterClass(ReplicatedScheduling.class);
  }

  /**
   * tryScheduleReplicatedEventsTask is the entrypoint to attempt to schedule a new work item for a replicated events
   * file to be processed.  It works out if it has room in the thread pool to attempt this work. It also queues skipped
   * items so that we know not to work ahead of ourselves later on with another file.
   * <p>
   * Logical responses:
   * 4 threads.  2Core 2 project-worker
   * AllProject, ProjectA, ProjectB
   * Schedule new entry as follows should give:
   * AllProjects - false
   * AllUsers - true
   * ProjectC - false
   * <p>
   * AllProject, AllUsers, ProjectB
   * AllProjects - false
   * AllUsers - false
   * ProjectC - true
   * ( NB only exception to this is if projectC has an already skipped event in our skip list,
   * we take it instead of this event, and mark it skipped instead, like a swap out. )
   *
   * @param firstEventInformation
   * @param eventsFileToProcess
   * @return
   */
  public ReplicatedEventTask tryScheduleReplicatedEventsTask(final EventWrapper firstEventInformation, final File eventsFileToProcess) {

    final String projectName = firstEventInformation.getProjectName();

    // check if we can schedule this task - if this project is already in progress in the queue already,
    // we do not schedule it, but instead return false, and this file is left alone and skipped over for now.
    if (Strings.isNullOrEmpty(projectName)) {
      throw new InvalidParameterException("projectName must be supplied and not null.");
    }

    // We could take a potentially dirty copy of what is in progress at this point in time,
    // We don't care if someone finishes while I make my decisions as long as we are the
    // only person scheduling new work - it doesn't matter.  Just ensure to keep the
    // schedule method below private and only used / protected by this method/lock.
    synchronized (getEventsFileInProgressLock()) {

      // We need to check have we got max number of threads / wip.  If we have don't queue any more at moment,
      // onto next file please.
      if (getNumEventFilesInProgress() >= replicatedConfiguration.getMaxNumberOfEventWorkerThreads()) {
        // we have the max number of threads in progress - just return now and we will re-queue this later.
        // Add this file to the skipped list, for decision making later on.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        log.debug("Not scheduling event file: {}. for project: {}, all workers busy.", eventsFileToProcess, projectName);
        return null;
      }

      // Another quick decision - should we be skipping this project entirely at the moment (for this iteration.)
      if (shouldStillSkipThisProjectForNow(eventsFileToProcess, projectName)) {
        // we have to skip all processing I could add this to the skipped list, and I will just for metrics of how many
        // skipped at the end.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        log.debug("Not scheduling event file: {}. for project: {}, as we are skipping / backing off this project for now.", eventsFileToProcess, projectName);
        return null;
      }

      if (eventsFilesInProgress.containsKey(projectName)) {
        // make a quick check to decide whether this is a skipped over file, or if its actually in progress from an earlier
        // iteration.
        ReplicatedEventTask wipTask = eventsFilesInProgress.get(projectName);
        if ( wipTask.getEventsFileToProcess() == eventsFileToProcess ){
          // this exact file is in progress - lets just skip over it, but DO NOT add to skip list as its in progress
          // if it succeeds its completed, and if it fails its prepended onto the start of the skipped list.
          log.debug("Not scheduling file: {} as its in progress for the project already. ", eventsFileToProcess);
          return null;
        }
        // We already have this project in progress, just schedule this as a skipped entry for this project so we can
        // come back to it.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        log.debug("Not scheduling event file: {}. for project: {}, as we have already a project in progress for this project.", eventsFileToProcess, projectName);
        return null;
      }

      boolean isCoreProject = isCoreProject(projectName);

      // If its a core project, and we haven't core project in progress - just schedule now - simple decision as we
      // have a reserved thread for these types.
      if (isCoreProject) {
        return scheduleReplicatedEventsTask(firstEventInformation, eventsFileToProcess);
      }

      // Now before we add it to the schedule, if its not in the reserved list, we are currently processing
      // a normal project.  So lets check our headroom i.e. NumWorkers - CoreProjectsInFlight.
      final int numCoreProjectsInProgress = getNumberOfCoreProjectsInProgress();
      final int numEventFilesInProgress = getNumEventFilesInProgress();

      if ((numEventFilesInProgress - numCoreProjectsInProgress) >= replicatedConfiguration.getNumberOfNonCoreWorkerThreads()) {
        // we are close to the limit - and no room for new non-core projects - bounce this one.
        addSkippedProjectEventFile(eventsFileToProcess, projectName);
        logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
            .log("Not scheduling event file: {}. for project: {}, as all (project-only) worker threads are busy for now, please consider increasing the worker pool. ConfigurationDetails: {}. ",
            eventsFileToProcess, projectName, replicatedConfiguration);
        return null;
      }

      // ok it looks like this project isn't in progress - lets go and queue it.
      return scheduleReplicatedEventsTask(firstEventInformation, eventsFileToProcess);
    }
  }

  /**
   * Used to get hold of the threadpool, useful to dump out the current state of the pool, num in queue,
   * num workers busy etc.
   *
   * @return
   */
  public ReplicatedThreadPoolExecutor getReplicatedWorkThreadPoolExecutor() {
    return replicatedWorkThreadPoolExecutor;
  }

  public boolean isAllWorkerThreadsActive() {
    return replicatedWorkThreadPoolExecutor.getActiveCount() == replicatedWorkThreadPoolExecutor.getMaximumPoolSize();
  }

  public boolean isCoreProject(String projectName) {
    return replicatedConfiguration.getCoreProjects().contains(projectName);
  }

  /**
   * Add a file for a given project to the skipped event files map per project, this allows correct ordering
   * so that a given event file can be plucked from the FIFO when the backoff failure period has elapsed.
   *
   * @param eventsFileToProcess
   * @param projectName
   */
  public void addSkippedProjectEventFile(File eventsFileToProcess, String projectName) {

    // This shouldn't be necessary to lock as the only person adding to skipped events and then working on it is the
    // same scheduling thread, but to protect us from future changes adding the lock here.
    synchronized (getEventsFileInProgressLock()) {
      if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
        getSkippedProjectsEventFiles().put(projectName, new ArrayDeque<File>());
        log.debug("Creating new ArrayDeque pointing key for project: {}", projectName);
      }

      // get the queue
      Deque<File> eventsBeingSkipped = getSkippedProjectsEventFiles().get(projectName);
      if (eventsBeingSkipped.contains(eventsFileToProcess)) {
        log.warn("Caller has attempted to add an already skipped over event file to the end of the list - track this down eventFile: %s.",
            eventsFileToProcess);
        return;
      }

      eventsBeingSkipped.addLast(eventsFileToProcess);
    }
  }

  /**
   * Add a file for a given project to the skipped event files map per project but to the start of the list.
   * This allows us to maintain correct ordering for failed events which need to go back onto the start of
   * the skipped over list, and not to the end
   *
   * @param eventsFileToProcess
   * @param projectName
   */
  public void prependSkippedProjectEventFile(File eventsFileToProcess, String projectName) {
    // This shouldn't be necessary to lock as the only person adding to skipped events and then working on it is the
    // same scheduling thread, but to protect us from future changes adding the lock here.
    synchronized (getEventsFileInProgressLock()) {
      if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
        getSkippedProjectsEventFiles().put(projectName, new ArrayDeque<File>());
        log.debug("PrependSkippedProjectEventFile: Creating new ArrayDeque pointing key for project: {}", projectName);
      }

      Deque<File> eventsBeingSkipped = getSkippedProjectsEventFiles().get(projectName);
      if (eventsBeingSkipped.contains(eventsFileToProcess)) {
        log.warn("Caller has attempted to prepend an already skipped over event file to the end of the list - track this down eventFile: %s.",
            eventsFileToProcess);
        return;
      }

      eventsBeingSkipped.addFirst(eventsFileToProcess);
    }
  }

  /**
   * Return the actual hashmap of all events being skipped for all projects.
   *
   * @return
   */
  public HashMap<String, Deque<File>> getSkippedProjectsEventFiles() {
    return skippedProjectsEventFiles;
  }


  public void removeSkippedProjectEventFile(File eventsFileToProcess, String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      log.warn("Unable to delete skipped event file for project {} as its already gone", projectName);
      return;
    }

    getSkippedProjectsEventFiles().get(projectName).remove(eventsFileToProcess);

    if (getSkippedProjectsEventFiles().containsKey(projectName) &&
        getSkippedProjectEventFilesList(projectName).isEmpty()) {
      // lets remove this project set now - so its null and no key exists pointing to it
      getSkippedProjectsEventFiles().remove(projectName);
      log.debug("Deleting pointing key as no items remain in the set for project: {}", projectName);
    }
  }

  public File getFirstSkippedProjectEventFile(String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      log.warn("Unable to delete skipped event file for project {} as its already gone", projectName);
      return null;
    }

    // get first item in our linked set.
    return getSkippedProjectsEventFiles().get(projectName).getFirst();
  }

  public Deque<File> getSkippedProjectEventFilesList(String projectName) {
    if (!getSkippedProjectsEventFiles().containsKey(projectName)) {
      log.warn("Unable to delete skipped event file for project {} as its already gone", projectName);
      return null;
    }

    // get first item in our linked set.
    return getSkippedProjectsEventFiles().get(projectName);
  }

  public boolean containsSkippedProjectEventFiles(String projectName) {
    return getSkippedProjectsEventFiles().containsKey(projectName);
  }

  public boolean isAllEventsFilesHaveBeenProcessedSuccessfully() {
    return allEventsFilesHaveBeenProcessedSuccessfully;
  }

  public void setAllEventsFilesHaveBeenProcessedSuccessfully(boolean allEventsFilesHaveBeenProcessedSuccessfully) {
    this.allEventsFilesHaveBeenProcessedSuccessfully = allEventsFilesHaveBeenProcessedSuccessfully;
  }

  public long getEventDirLastModifiedTime() {
    return eventDirLastModifiedTime;
  }

  public void setEventDirLastModifiedTime(long eventDirLastModifiedTime) {
    this.eventDirLastModifiedTime = eventDirLastModifiedTime;
  }

  public int getNumEventFilesInProgress() {
    return eventsFilesInProgress.size();
  }

  public int getNumberOfCoreProjectsInProgress() {
    int numCoreProjectsInProgress = 0;

    for (String project : replicatedEventsCoordinator.getReplicatedConfiguration().getCoreProjects()) {
      if (eventsFilesInProgress.containsKey(project)) {
        // its got this project bump core project wip counter.
        numCoreProjectsInProgress++;
      }
    }

    return numCoreProjectsInProgress;
  }

  /**
   * we have skipped processing events if we have anything in the skipped in this iteration flagged,
   * or anything in the skipped events list.
   *
   * @return
   */
  public boolean hasSkippedAnyEvents() {
    return !(skipProcessingAndBackoffThisProjectForNow.isEmpty() && skippedProjectsEventFiles.isEmpty());
  }

  /**
   * Create a new ReplicatedEventTask and schedule it to be picked
   * up by the waiting thread pool of workers.
   *
   * @param eventInformation
   * @param eventsFileToProcess
   */
  private ReplicatedEventTask scheduleReplicatedEventsTask(final EventWrapper eventInformation, final File eventsFileToProcess) {
    // It should always be protected and only called from trySchedule, calling lock again will have no impact
    // when our thread holds the lock already - shouldn't need reentrant locking.
    synchronized (getEventsFileInProgressLock()) {
      // create the task and add it to the queue for awaiting thread pool to pick up when free / might even
      // start up a new thread, if we are less than max pool size.
      final String projectName = eventInformation.getProjectName();

      // This is a cool piece of logic where we have worked out we can do a project e.g. projectC, but if we have a event
      // already skipped for this said project we can swap it out and do that event now instead...
      final File eventsFileToReallyProcess = checkSwapoutSkippedProjectEvent(eventsFileToProcess, projectName);

      // if something went wrong we can skip a project even at this late state..
      if (eventsFileToReallyProcess == null) {
        log.warn("Something went wrong considering event file: {}, it will be picked up later.", eventsFileToReallyProcess);
        return null;
      }

      // Otherwise create a new task for this event file given and lets schedule/execute it now.
      final ReplicatedEventTask newTask = new ReplicatedEventTask(projectName, eventsFileToReallyProcess, replicatedEventsCoordinator);

      // We only call execute if we actually want to run these remote tasks. for testing I allow them to be queued, but not
      // really removed from the queue by the remote thread - easiest way is to check the indexer really running state.
      if (replicatedEventsCoordinator.isGerritIndexerRunning()) {
        // Keep this order, if it fails to queue the task - it will throw, and not add to inProgress map.
        replicatedWorkThreadPoolExecutor.execute(newTask);
      }
      addEventsFileInProgress(newTask);
      return newTask;
    }
  }

  /**
   * DO NOT CALL THIS for anything other than testing....
   *
   * @param newTask
   */
  public void addForTestingInProgress(final ReplicatedEventTask newTask) {
    addEventsFileInProgress(newTask);
  }

  /**
   * Add a project specific events task to our list of Events in progress.
   *
   * @param newTask
   */
  private void addEventsFileInProgress(final ReplicatedEventTask newTask) {
    if (newTask == null) {
      log.error("Null task supplied - can't do anything with this.");
      return;
    }

    // We only put the events file in progress into the WIP map after we call execute as we are in a lock here, and if
    // execute throws because the event can't be queued we dont want it in this map of in progress.
    synchronized (eventsFilesInProgress) {
      if (eventsFilesInProgress.containsKey(newTask.getProjectname())) {
        log.warn("RE eventsFileInProgress already contains a project ReplicatedEventTask {} for this project, attempting self heal!", newTask);
      }
      // this will force update the WIP - we should never be overwriting an entry - means we have scheduled the same project
      // twice - which isn't correct.investigate.
      eventsFilesInProgress.put(newTask.getProjectname(), newTask);
    }
  }

  /**
   * Clear out an event from being in progress so we can queue another one.
   * <p>
   * Normally a failure to remove we want to log, but the item might already have been deleted, so we pass
   * and allow items to not exist as an option (used by afterExecute) to make sure our list stays clean and can heal
   * in unforseen circumstances.
   *
   * @param newTask
   * @param allowItemToNotExist
   */
  public void clearEventsFileInProgress(ReplicatedEventTask newTask, boolean allowItemToNotExist) {

    synchronized (eventsFilesInProgress) {
      // We only put the events file in progress into the WIP map after we call execute as we are in a lock here, and if
      // execute throws because the event can't be queued we dont want it in this map of in progress.
      if (!eventsFilesInProgress.remove(newTask.getProjectname(), newTask)) {
        if (allowItemToNotExist) {
          return;
        }
        log.error("Something strange happened clearing out wip for events file: {}, clearing this entire project to recover.", newTask);
        eventsFilesInProgress.remove(newTask.getProjectname());
      }
    }
  }

  /**
   * Returns a shallow copy of the items in the map, so we have a snapshot in time of what
   * was inprogress - usually taken to match the current listing of event files information.
   *
   * @return
   */
  public HashMap<String, ReplicatedEventTask> getCopyEventsFilesInProgress() {
    // Take a copy now to protect against changes later on.
    // take under lock to prevent external effect.
    synchronized (getEventsFileInProgressLock()) {
      HashMap<String, ReplicatedEventTask> shallowCopy = new HashMap<>(); // this isn't concurrent as doesn't get updated.
      shallowCopy.putAll(eventsFilesInProgress);
      return shallowCopy;
    }
  }

  /**
   * We use the literal class we are updating as the lock as its eagerly initialized
   * and always present. But could also be any static object.
   *
   * @return
   */
  public Object getEventsFileInProgressLock() {
    return eventsFilesInProgress;
  }

  /**
   * TRUE = Contains a event in progress for this project.
   *
   * @param projectname
   * @return
   */
  public boolean containsEventsFileInProgress(final String projectname) {
    // do we have a WIP for this project.
    return eventsFilesInProgress.containsKey(projectname) && (eventsFilesInProgress.get(projectname) != null);
  }

  /**
   * Very specific code, to check if this project has skipped projects already in existance.
   * If it does it adds this event file to the end of the skipped list and then takes the first one out FIFO,
   * therefore it will perform the file it just took of the list as the next event to be processed keeping existing
   * ordering for this project intact.
   *
   * @param eventsFileToProcess
   * @param projectName
   * @return
   */
  private File checkSwapoutSkippedProjectEvent(File eventsFileToProcess, String projectName) {
    // Only one caller / thread ever checks this, and only the same thread can swap out,
    // so no locking needed currently but for clarity I am locking - it wont add contention but helps for clarity
    // that we can't be interrupted.
    synchronized (getEventsFileInProgressLock()) {
      if (!skippedProjectsEventFiles.containsKey(projectName)) {
        return eventsFileToProcess;
      }

      // time to swap it out.
      addSkippedProjectEventFile(eventsFileToProcess, projectName);

      File eventsFileToReallyProcess = getFirstSkippedProjectEventFile(projectName);

      if (eventsFileToReallyProcess == eventsFileToProcess) {
        log.error("Invalid ordering of events processing discovered, where it picked up last event as LIFO not FIFO. File: {}", eventsFileToProcess);
        // Failing this entire project, and do no more processing on it.
        // When we finish this iteration we can pick up this project fresh with new state - and hopefully recover!
        addSkipThisProjectsEventsForNow(projectName);
        return null;
      }

      // lets remove the item we got back.
      removeSkippedProjectEventFile(eventsFileToReallyProcess, projectName);
      log.info("Swapped out supplied file: %s for an earlier file in the skipped list: %s",
          eventsFileToProcess, eventsFileToReallyProcess);
      return eventsFileToReallyProcess;
    }
  }

  /**
   * Clear the entire list of skipped over event files - this is created uniquely per iteration.
   */
  public void clearSkippedProjectsEventFiles() {
    skippedProjectsEventFiles.clear();
  }

  /**
   * Skip over this projects events for all events, this one and upcoming until we finish this processing iteration
   * then we can re-evaluate it all again. Useful for backing off a project while the DB catches up.
   *
   * @param projectName
   */
  public synchronized void addSkipThisProjectsEventsForNow(final String projectName) {
    skipProcessingAndBackoffThisProjectForNow.put(projectName, new ProjectBackoffPeriod(projectName, replicatedConfiguration));
  }

  /**
   * clear all projects from the list,
   * we do this when we start a new iteration of the WIP / events files.
   */
  public synchronized void clearAllSkipThisProjectsEventsForNow() {
    skipProcessingAndBackoffThisProjectForNow.clear();
  }

  /**
   * clear just this specific project from the list of skipped events information
   * Used when a event file has maxed out its retries and moves to failed, or in fact has processed successfully.
   */
  public synchronized void clearSkipThisProjectsEventsForNow(final String projectName) {
    skipProcessingAndBackoffThisProjectForNow.remove(projectName);
  }

  /**
   * Returns true if this project should have no further events processed for it, for this iteration.
   *
   * @param projectName
   * @return
   */
  public synchronized boolean containsSkipThisProjectForNow(final String projectName) {
    return skipProcessingAndBackoffThisProjectForNow.containsKey(projectName);
  }

  /**
   * used by tests for confirmation of data - mostly the real code uses the shouldStillSkipThisProjectForNow below.
   *
   * @param projectName
   * @return ProjectBackoffPeriod
   */
  public synchronized ProjectBackoffPeriod getSkipThisProjectForNowBackoffInfo(final String projectName) {
    return skipProcessingAndBackoffThisProjectForNow.get(projectName);
  }

  /**
   * get the current time in ms, and compare against a project in the skip list start time.
   * <p>
   * if no project in list ( isn't a real value use case ) is used, it will return false, as
   * if a project was there but is no longer to be skipped for ease of use.
   *
   * @param projectName
   * @return
   */
  public synchronized boolean shouldStillSkipThisProjectForNow(final File eventsFileToProcess, final String projectName) {
    if (!skipProcessingAndBackoffThisProjectForNow.containsKey(projectName)) {
      return false;
    }
    ProjectBackoffPeriod skipInfo = skipProcessingAndBackoffThisProjectForNow.get(projectName);

    long startTime = skipInfo.getStartTimeInMs();
    long currentTime = System.currentTimeMillis();
    long maxBackOffPeriod = skipInfo.getCurrentBackoffPeriodMs();

    if ((currentTime - startTime) > maxBackOffPeriod) {
      // Decided to retry this project eventually.
      log.debug("Decided we might be able to reschedule work for skipped project: {} for eventFile: {}",
          skipInfo.toString(), eventsFileToProcess);
      return false;
    }

    return true;
  }
}
