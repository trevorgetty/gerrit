package com.google.gerrit.common.replication.workers;

import com.google.gerrit.common.FailedEventUtil;
import com.google.gerrit.common.ReplicatedEventsFileFilter;
import com.google.gerrit.common.replication.ProjectBackoffPeriod;
import com.google.gerrit.common.replication.ReplicatedEventTask;
import com.google.gerrit.common.replication.ReplicatedScheduling;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsDBNotUpToDateException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsMissingChangeInformationException;
import com.google.gerrit.common.replication.processors.GerritPublishable;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.ReplicatorMetrics;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.ChainedEventComparator;
import com.wandisco.gerrit.gitms.shared.events.EventNanoTimeComparator;
import com.wandisco.gerrit.gitms.shared.events.EventTimestampComparator;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.exceptions.InvalidEventJsonException;
import com.google.common.flogger.FluentLogger;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static com.google.gerrit.common.replication.ReplicationConstants.INCOMING_EVENTS_FILE_PREFIX;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedIncomingEventWorker implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ReplicatedEventsFileFilter incomingEventsToReplicateFileFilter =
      new ReplicatedEventsFileFilter(INCOMING_EVENTS_FILE_PREFIX);

  // Local Instance Vars.
  private final ReplicatedConfiguration replicatedConfiguration;
  private final ReplicatedEventsCoordinator replicatedEventsCoordinator;
  private final Gson gson;

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
  public ReplicatedIncomingEventWorker(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
    this.gson = replicatedEventsCoordinator.getGson();
    SingletonEnforcement.registerClass(ReplicatedIncomingEventWorker.class);
  }

  /**
   * Main thread which will also look for files coming from the replicator,
   * which need to be read and published as they are incoming events. This is scheduled periodically.
   */
  @Override
  public void run() {
    try {
      logger.atFinest().log("Checking for incoming events to read and publish");
      readAndPublishIncomingEvents();
      logger.atFinest().log("Finished readAndPublishIncomingEvents()");
    } catch (RuntimeException ex) {
      logger.atSevere().withCause(ex).log();
    } catch (Throwable t) {
      if (t instanceof InterruptedException) {
        logger.atInfo().log("Asked to shutdown this thread. ");
        Thread.currentThread().interrupt();
        return;
      }

      // we dont want to take the main process of gerrit out - let it try to recover.
      logger.atSevere().withCause(t).log("Worker experienced exception - attempting to recover.");
    }
  }


  /**
   * Look for files written by the replicator in the right directory and read
   * them to publish the contained events files as a ReplicatedEventTask or piece of work to be done by the thread pool
   * of event workers.
   */
  private void readAndPublishIncomingEvents() {
    if (!replicatedConfiguration.getIncomingReplEventsDirectory().exists()) {
      logger.atSevere().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS).log(
          "Incoming replicated events directory [ %s ] cannot be found. Replicated events will not work!",
          replicatedConfiguration.getIncomingReplEventsDirectory().getAbsolutePath());
      return;
    }

    ReplicatedScheduling replicatedScheduling = replicatedEventsCoordinator.getReplicatedScheduling();

    // Make sure we clear out for a new iteration every time, note we can't clear the backoff list it needs persisted.
    // The WIP can't be cleared it should only be added to here, but cleared by the worker that processes it.
    // so that only leaves the skip list to be cleared down per iteration.
    replicatedScheduling.clearSkippedProjectsEventFiles();

    try {
      File[] listFiles;
      HashMap<String, ReplicatedEventTask> dirtyCopyOfWIP;

      // Overview: Take a dirty copy of WIP to match the listed files in directory at this snapshot in time.
      // Reason: By the time we hit here, we need to build a picture of the WIP in progress now, as it might
      // change in a few seconds time, we need to lock that in effect.  The reason is that the worker
      // threads might be working on a previous event file - and as such we don't want to read it and send it
      // off for processing again!
      synchronized (replicatedScheduling.getEventsFileInProgressLock()) {

        logger.atFinest().log("Starting new iteration of event processing.");

        logger.atFine().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
            .log("RE Event worker pool information : %s", replicatedScheduling
                .getReplicatedWorkThreadPoolExecutor().toString());

        // Ok before I go making more expensive calls there are a few quick checks that prevent us needing
        // to do anything.
        // 1) Are all threads busy
        // 2) Is the mtime on the directory any later than the last time we processed ALL the files.
        //    N.B. All the files means if it error'd last time we will have an error flag that will indicate
        //    that not all files have been processed correctly in the directory.  allEventsFilesProcessed = false
        //   It will only become true when num files in directory = 0, and no WIP in progress where we set it finally to true.
        if (replicatedScheduling.isAllWorkerThreadsActive()) {
          // Logging our information which could flood the logs at most every 5 mins.
          logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
              .log("All event worker threads are active, we can't schedule more work at this time.");
          return;
        }

        // Before listing the directory - get mtime on directory...
        // Take again after the listing, if its changed - we could have time for either before or after or between!
        final long eventDirModified = replicatedConfiguration.getIncomingReplEventsDirectory().lastModified();

        // before we do the directory listing - check can we skip it successfully.
        if (replicatedScheduling.isAllEventsFilesHaveBeenProcessedSuccessfully() &&
            eventDirModified == replicatedScheduling.getEventDirLastModifiedTime()) {
          // the directory content hasn't changed and all files have been processed - lets just ignore for now until
          // directory time has changed.  Don't flood the logs with this message every 500ms though!
          logger.atFine().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
              .log("RE Not scanning events directory as last modified " +
                  "time is still the same and all events were previously processed.");
          return;
        }

        // Ok we have either not processed all files previously or the mtime has just updated on the directory, either
        // way get an event dir listing now.
        listFiles = replicatedConfiguration.getIncomingReplEventsDirectory()
            .listFiles(incomingEventsToReplicateFileFilter);

        final long eventDirModifiedAfterListing = replicatedConfiguration.getIncomingReplEventsDirectory().lastModified();

        if (listFiles == null) {
          logger.atSevere().log("RE There were no matching files found in the [ %s ] directory",
              replicatedConfiguration.getIncomingReplEventsDirectory());

          // ensure we set that not all files have been processed.
          replicatedScheduling.setAllEventsFilesHaveBeenProcessedSuccessfully(false);
          return;
        }

        // Whether there are some or no event files we can record the time we listed them out now
        // for use in decisions next time we come in here.
        replicatedScheduling.setEventDirLastModifiedTime(eventDirModified);

        if (listFiles.length == 0) {
          // No files found - lets get out of here.
          // indicate all files have been processed successfully finally, there can't be any WIP,
          // as there are no files in the directory.  Indicate all processed now and we will stop listing until dir mtime
          // actually gets updated.
          replicatedScheduling.setAllEventsFilesHaveBeenProcessedSuccessfully(true);
          dirtyCopyOfWIP = replicatedScheduling.getCopyEventsFilesInProgress();

          if (!dirtyCopyOfWIP.isEmpty()) {
            // this condition would indicate someone deleting the event file before actually taking the lock and
            // updating the WIP list.  This should all be done in a locked state to prevent race conditions such as this.!
            logger.atWarning().log(
                "Strange condition - no event files in event directory which indicates all events processed, but WIP shows size %s.", dirtyCopyOfWIP.size());
          }

          return;
        }

        // As soon as we have any files to be processed the isAllEventsFilesHaveBeenProcessedSuccessfully
        // must be set to false, as work has arrived.
        replicatedScheduling.setAllEventsFilesHaveBeenProcessedSuccessfully(false);

        if (eventDirModified != eventDirModifiedAfterListing) {
          // time before listing and after listing doesn't match so it changed between times, so I can't
          // assume what time the directory was listed at... I should record the earlier time that
          // way I will be guaranteed to check the directory again at least once at the end, this is here
          // just to signal this has happened in logging.
          logger.atInfo().log("Events directory last updated %s but it changed during listing to %s," +
                  "so we have noted the earlier time, which will force a scan next time around.",
              eventDirModified, eventDirModifiedAfterListing);
        }

        // Great now take a snapshot of what is in progress.
        dirtyCopyOfWIP = replicatedScheduling.getCopyEventsFilesInProgress();

        // This log line is a debug line as it can appear to often on backoff retires.
        logger.atFine().log("RE Found [ %s ] event files to be processed, inProgress count is: [ %s ]",
            listFiles.length, dirtyCopyOfWIP.size());

        // If the wip count matches the listed directory count - then they are all already in progress.
        if (dirtyCopyOfWIP.size() == listFiles.length) {
          logger.atInfo().atMostEvery(
              replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS).log(
              "All event files in directory are already in progress, exiting.");
          return;
        }
      } // release the lock - we have got our copy of all info required.

      // for performance reasons, lets build a tmp copy of the WIP Files not tasks.
      // this can be built each time, to avoid nested for loops and is disposable.  It protects against
      // real file system reads below for a WIP event file.
      Collection<File> dirtyCopyOfWIPFiles = buildDirtyWIPFiles(dirtyCopyOfWIP);

      // Make sure our list of files is now in time order.
      Arrays.sort(listFiles);

      // Lets process each file in time order.  We will check if we can scheduling work for a given project,
      // if not it will be skipped over and remembered, in case we hit another event later for same project.
      // This allows us to keep the correct ordering by swapping out skipped over event files if required.
      for (File file : listFiles) {

        // note we check the old list, so our snapshot of file listings, checks the wip at the time,
        // if we get a wip hit, we just skip that item, it may already have finished but that's ok and its
        // why we do not check the current WIP list, this is much better for performance.
        if (dirtyCopyOfWIPFiles.contains(file)) {
          logger.atFinest().log("EventFile: [ %s ] was already in progress, skipping it", file.getName());
          continue;
        }

        logger.atFinest().log("EventFile: being read: [ %s ] - will attempt to schedule.", file.getName());

        try (ByteArrayOutputStream bos =
                 readFileToByteArrayOutputStream(file, replicatedConfiguration.isIncomingEventsAreGZipped())) {

          // we used to process the events directly - but instead, we need to
          // check what projects its for,
          // and try to hand off this file for another thread to do the
          // processing.
          EventWrapper firstEventInformation =
              getFirstEventInformation(bos.toByteArray());

          // I don't want to be handing memory around - much better to send the
          // File information across to the processing thread, and let it read the information fresh.
          // The worker is the only person that will finish and  delete this event file when finished processing it
          // completely or successfully or on final failure when it moves it to the failed directory.
          ReplicatedEventTask eventTask = replicatedScheduling.tryScheduleReplicatedEventsTask(firstEventInformation, file);

          // if null, we can't queue this project yet, try the next one.
          // All the logic around backoff, skipping and swapping events for ordering etc is within trySchedule above.
          if (eventTask == null) {
            // This project is already in progress, or has been added to a skipped list for later consideration.
            continue;
          }

          // now we should take a decision on whether we have hit saturation and exit early?
          if (replicatedEventsCoordinator.getReplicatedScheduling().isAllWorkerThreadsActive()) {
            // all our threads are busy - lets bail out nothing more we can do in this iteration at all.
            // Move this to be a log every?
            logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS)
                .log("All Event worker threads in the pool are now busy");
            return;
          }

        } catch (InvalidEventJsonException | IOException e) {
          // The file contains invalid JSON. There is nothing else we can do other than move the file
          // to the failed directory.
          FailedEventUtil.moveFileToFailed(replicatedConfiguration, file);
          throw new ReplicatedEventsImmediateFailWithoutBackoffException(e.getMessage());
        }
      }

      // flogger this every x ms.
      logger.atInfo().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS).log(
          "Have processed all event data and skipped over none: [ %s ]", !replicatedEventsCoordinator
              .getReplicatedScheduling().hasSkippedAnyEvents());

    } catch (Exception e) {
      logger.atSevere().withCause(e).log("RE error while reading events from incoming queue");
    }
  }

  /**
   * Build a temp copy of the WIP as a collection of files, not ReplicatedEventTask(s).
   *
   * @param dirtyCopyOfWIP
   * @return
   */
  public static Collection<File> buildDirtyWIPFiles(HashMap<String, ReplicatedEventTask> dirtyCopyOfWIP) {
    Collection<File> dirtyCopyOfWIPFiles = new ArrayList<>(dirtyCopyOfWIP.size());
    for (ReplicatedEventTask tmpTask : dirtyCopyOfWIP.values()) {
      dirtyCopyOfWIPFiles.add(tmpTask.getEventsFileToProcess());
    }
    return dirtyCopyOfWIPFiles;
  }

  /**
   * From the bytes we read from disk, we get passed the created EventWrapper list in a sorted order.
   * We then process this list, and send them off to the appropriate processessors, to handle index/account/project
   * type events differently.
   * <p>
   * N.B. Error handling descriptions and processing are described by GER-1483 / GER-1769.
   *
   * @param replicatedEventTask
   * @param sortedEvents
   */
  private int publishEvents(final List<EventWrapper> sortedEvents, ReplicatedEventTask replicatedEventTask) throws ReplicatedEventsImmediateFailWithoutBackoffException {
    logger.atFinest().log("RE Trying to publish original events...");

    int failedEvents = 0;
    boolean useFailImmediately = false;
    boolean isDbStale = false;
    boolean isLastRetry = isLastBackoffRetry(replicatedEventTask);

    // handy quick accessors.
    final File eventsFileBeingProcessed = replicatedEventTask.getEventsFileToProcess();
    final ReplicatedScheduling replicatedScheduling = replicatedEventsCoordinator.getReplicatedScheduling();

    List<EventWrapper> processedEvents = new LinkedList<>();

    for (EventWrapper originalEvent : sortedEvents) {
      try {
        ReplicatorMetrics.totalPublishedForeignEvents.incrementAndGet();
        ReplicatorMetrics.totalPublishedForeignGoodEvents.incrementAndGet();
        ReplicatorMetrics.totalPublishedForeignEventsByType
            .add(originalEvent.getEventOrigin());

        if (originalEvent
            .getEventOrigin() == EventWrapper.Originator.DELETE_PROJECT_MESSAGE_EVENT) {
          continue;
        }

        Set<GerritPublishable> clients =
            replicatedEventsCoordinator.getReplicatedProcessors().get(originalEvent.getEventOrigin());

        if (clients == null) {
          final String err = String.format("No event publishers available for this event origin. %s ", originalEvent);
          logger.atSevere().log(err);
          // treat no processor for this type - like class not found, fail without backoff as this wont change.
          throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
        }

        // In 2.16 this clients list is a single client - even here there is a 1:1 match on processor to origin
        // we just didn't rework all the calling code to add risk in 2.13 during event rewrite.
        for (GerritPublishable gp : clients) {
          gp.publishIncomingReplicatedEvents(originalEvent);
          processedEvents.add(originalEvent);
        }
      } catch (JsonSyntaxException e) {
        // JsonSyntax inside an event wrapper is deemed to be not transient and wont change, as such
        // dont backoff (so dont break out), lets just try the remaining items now and fail.
        logger.atSevere().withCause(e).log(
            "RE event has been lost. Could not rebuild obj using GSON %s", originalEvent);
        failedEvents++;
        useFailImmediately = true;
      } catch (ReplicatedEventsImmediateFailWithoutBackoffException e) {
        failedEvents++;
        useFailImmediately = true;
      } catch (ReplicatedEventsMissingChangeInformationException e) {
        // indicate failure on this event file group to back it off, increment the failure counter,
        // update the event file to remove any passed/processed successfully events.
        // it may end up with the remainder moving to the failed directory unlike DbStale below which HAS to keep
        // the file until the DB is finally up to date!
        failedEvents++;
        // ok we didn't find some change information - for this case we want to remove the items we have up until this point.
        logger.atWarning().withCause(e).log(
            "RE Unable to process events file: %s completely, there was a temporary failure " +
                "indicated by missing changes.", eventsFileBeingProcessed);

        // If we are on the last retry - then allow all events to be attempted by not breaking out at this one.
        if (!isLastRetry) {
          break;
        }
      } catch (ReplicatedEventsDBNotUpToDateException e) {
        // indicate failure on this event file group to back it off, increment the failure counter, but dont finally delete/move
        // to failed as the Db is stale - indicate this to keep this file and project blocked until Db is updated.
        failedEvents++;
        isDbStale = true;
        logger.atWarning().withCause(e).log(
            "RE Unable to process events file: %s, there was temporary failure indicated by the DB not being up to date.", eventsFileBeingProcessed);
        // Break now as DbStale means it doesn't make sense to process any other events after this.
        break;
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("RE Unable to process events file: %s,  Unexpected Error while processing an event: %s. using failure backoff to retry.",
            eventsFileBeingProcessed, originalEvent);
        failedEvents++;
        // If we are on the last retry - then allow all events to be attempted by not breaking out at this one.
        if (!isLastRetry) {
          break;
        }
      }
    }

    // Decide on what to do when failures happen.
    // See GER-1483 / GER-1769 for info on how to handle errors in the index events.
    // Basically failures falls into these categories:
    // All success - just clear out as normal.
    // DBStale for some reason - backoff but never finally FAIL and delete/move the file until DB is up to date.
    // JsonProcessing / Class Not found exception - Immediately fail type errors which wont change with backoffs,
    //                                              so attempt remainder in file and move reaminder to Failed
    // Transient failures(Default Behaviour) - backoff with increasing time periods until eventually it hits last retry
    //                                          it then attempts to process remaining items and then it can be moved to failed.
    if (failedEvents > 0) {
      logger.atWarning().log(
          "RE There was %s failed event(s) in replicated event task %s, checking failure behaviour now. UseFailImmediately: %s, DbConsideredStale: %s",
          failedEvents, replicatedEventTask.toFriendlyInfo(), useFailImmediately, isDbStale);

      checkForFailureBackoff(replicatedEventTask, replicatedScheduling, isDbStale, useFailImmediately, sortedEvents, processedEvents);
      return failedEvents;
    }

    // All worked just as it should, lets delete the file here.
    logger.atInfo().log("RE Completed processing replicated event task [ %s ] successfully.",
        replicatedEventTask.toFriendlyInfo());
    // lets lock and delete the file along with update the in progress map as a joint operation.
    synchronized (replicatedScheduling.getEventsFileInProgressLock()) {
      attemptDeleteEventFile(eventsFileBeingProcessed);
      replicatedScheduling.clearEventsFileInProgress(replicatedEventTask, false);
    }

    // return no failures.
    return 0;
  }

  /**
   * Useful check to understand if we are on the final failure Backoff Attempt and if so we can use this
   * information to best attempt things like remainder processing of a failed event file.
   *
   * @param replicatedEventTask
   * @return
   */
  private boolean isLastBackoffRetry(final ReplicatedEventTask replicatedEventTask) {
    if (replicatedEventsCoordinator.getReplicatedScheduling().containsSkipThisProjectForNow(replicatedEventTask.getProjectname())) {
      ProjectBackoffPeriod backoffPeriod = replicatedEventsCoordinator.getReplicatedScheduling().getSkipThisProjectForNowBackoffInfo(replicatedEventTask.getProjectname());

      // Lets check if we can fail further.
      return backoffPeriod.getNumFailureRetries() >= replicatedConfiguration.getMaxIndexBackoffRetries();
    }
    return false;
  }

  /**
   * Method recreate wrapped events and builds a list of EventData objects which
   * are used to sort upon. EventData object contain the eventTimestamp,
   * eventNanoTime of the event along with the EventWrapper object. A sort is
   * performed using a comparator which sorts on both times of the object.
   *
   * @param eventsBytes
   * @return
   * @throws IOException
   */
  private List<EventWrapper> checkAndSortEvents(byte[] eventsBytes)
      throws InvalidEventJsonException {

    List<EventWrapper> eventDataList = new ArrayList<>();
    String[] events =
        new String(eventsBytes, StandardCharsets.UTF_8).split("\n");

    for (String event : events) {

      if (event == null) {
        throw new InvalidEventJsonException(
            "Event file is invalid, missing / null events.");
      }

      EventWrapper originalEvent;
      try {
        originalEvent = gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e) {
        throw new InvalidEventJsonException(
            String.format("Event file contains Invalid JSON. \"%s\", \"%s\"",
                event, e.getMessage()));
      }

      // Only adding instances of EventWrapper to the list for sorting after they've had their event JSON
      // checked for validity.
      if (checkValidEventWrapperJson(originalEvent)) {
        eventDataList.add(originalEvent);
      }
    }

    //sort the event data list using a chained comparator.
    Collections.sort(eventDataList,
        new ChainedEventComparator(
            new EventTimestampComparator(),
            new EventNanoTimeComparator()));

    return eventDataList;
  }


  /**
   * Check that the event JSON as part of the EventWrapper is well formed. If not throw and InvalidEventJsonException.
   *
   * @param originalEvent : The EventWrapper instance. Calling getEvent() will get event string for
   *                      the instance.
   * @return true if valid event JSON
   * @throws InvalidEventJsonException if invalid event JSON.
   */
  private boolean checkValidEventWrapperJson(EventWrapper originalEvent) throws InvalidEventJsonException {
    if (originalEvent == null) {
      throw new InvalidEventJsonException("Internal error: event is null after deserialization");
    }
    // If the JSON is invalid we will not have been able to get eventTimestamp or eventNanoTime information
    // from it required for sorting, so all we can do is throw an exception here. If the JSON is empty this case
    // will cover {} or ""
    if (originalEvent.getEvent().length() <= 2) {
      throw new InvalidEventJsonException("Internal error, event JSON is invalid ");
    }

    return true;
  }


  /**
   * Method takes the event information in bytes, and reads the first event
   * wrapper it can get. Now we aren't really interested in the EventData, we
   * just want to know which project its for which is kept in the EventWrapper
   * info. All events in a given file are for a single project!
   *
   * @param eventsBytes
   * @return EventWrapper wrapper information for the first event read.
   * @throws InvalidEventJsonException
   */
  private EventWrapper getFirstEventInformation(final byte[] eventsBytes) throws InvalidEventJsonException {

    final String[] events =
        new String(eventsBytes, StandardCharsets.UTF_8).split("\n");

    for (String event : events) {

      if (event == null) {
        throw new InvalidEventJsonException(
            "Event file is invalid, missing / null events.");
      }


      try {
        return gson.fromJson(event, EventWrapper.class);
      } catch (JsonSyntaxException e) {
        throw new InvalidEventJsonException(
            String.format("Event file contains Invalid JSON. \"%s\", \"%s\"",
                event, e.getMessage()));
      }
    }

    // Now valid event information in the file - this is invalid.
    throw new InvalidEventJsonException(
        "Event file contains Invalid JSON with no event wrappers.");
  }

  /**
   * Process the event information we have been given in bytes. This includes
   * reading the file into an events list, and then processing / publishing the
   * information.
   *
   * @return True:False If we process all events we return true, and we delete
   * the file as its finished processing If we process, with any
   * failures we return false, and put the events file off into the
   * failure directory.
   */
  public void processEventInformationBytes(final byte[] eventsBytes, ReplicatedEventTask replicatedEventTask) {

// Here for handy use, makes code a bit nicer to read.
    final File eventsFileBeingProcessed = replicatedEventTask.getEventsFileToProcess();
    final ReplicatedScheduling replicatedScheduling = replicatedEventsCoordinator.getReplicatedScheduling();

    // Please note the failure / decision making has been placed here to make it centralized, but equally the
    // ReplicatedThreadPool before/afterExecute would allow us to also make decisions on each worker as it shuts down
    // I am keeping here for now for simplicity of understanding, and only a mop up belts and braces clear of WIP is in
    // after execute.  Although I do use it for timing of our event tasks for metrics, which keeps this code cleaner!
    try {
      ReplicatorMetrics.totalPublishedForeignEventsBytes.addAndGet(eventsBytes.length);
      ReplicatorMetrics.totalPublishedForeignGoodEventsBytes.addAndGet(eventsBytes.length);
      ReplicatorMetrics.totalPublishedForeignEventsProsals.incrementAndGet();

      List<EventWrapper> sortedEvents;
      try {
        sortedEvents = checkAndSortEvents(eventsBytes);
      } catch (InvalidEventJsonException e) {
        throw new ReplicatedEventsImmediateFailWithoutBackoffException("Exception processing file - move to the failed directory as we can't deserialize it", e);
      }

      if (sortedEvents == null) {
        // something went wrong??? No events to process - empty created file?
        throw new ReplicatedEventsImmediateFailWithoutBackoffException("Exception processing file - move to the failed directory as we can't read contents correctly.");
      }

      // indicate number of events in this event file.
      replicatedEventTask.setNumEventsToProcess(sortedEvents.size());

      // All processing of failures now should happen closer to the each event being processed inside publishEvents.
      // Just incase we need to filter out completed events.  So the only exception we ever handle here is a corruption
      // case which supports direct move to the failed directory below.
      publishEvents(sortedEvents, replicatedEventTask);

    } catch (ReplicatedEventsImmediateFailWithoutBackoffException e) {
      logger.atSevere().withCause(e).log(
          "RE There was a unrecoverable failure in replicated task [ %s ], " +
              "this entire file is being moved to the failed directory so it can be manually investigated later.", replicatedEventTask.toFriendlyInfo());
      synchronized (replicatedScheduling.getEventsFileInProgressLock()) {
        FailedEventUtil.moveFileToFailed(replicatedConfiguration, eventsFileBeingProcessed);
        replicatedScheduling.clearEventsFileInProgress(replicatedEventTask, false);
      }
    }
  }

  public void checkForFailureBackoff(final ReplicatedEventTask replicatedEventTask,
                                     final ReplicatedScheduling replicatedScheduling,
                                     boolean isDBStale,
                                     boolean useFailImmediately, // Fail immediately without any backoff
                                     final List<EventWrapper> allEventsBeingProcessed, // only here for performance/rewrite prevention.
                                     final List<EventWrapper> correctlyProcessedEvents) {
    final String projectName = replicatedEventTask.getProjectname();
    final File eventsFileBeingProcessed = replicatedEventTask.getEventsFileToProcess();

    // Potentially update the file content without the items which succeeded, or maybe there are no changes
    // this updates the file atomically - it DOES NOT move the file to failed here.
    checkPersistRemainingEntries(replicatedEventTask, allEventsBeingProcessed, correctlyProcessedEvents);

    // Decide failure behaviour - should we just back off this event file further, or should it be moved finally
    // to the failed directory.
    if (!replicatedScheduling.containsSkipThisProjectForNow(projectName)) {
      if (useFailImmediately && !isDBStale) {
        // Fail immediately can happen without backoff - its essentially like being on the final backoff and treated
        // as such - but there is nothing to stop us being in backoff, then hitting this error later, so we need to
        // handle fail immediately WITH and WITHOUT this project being in the backoff list already. !
        logger.atWarning().log("RE Task [ %s ] has failures, we have indicated to fail immediately with remaining failures.",
            replicatedEventTask.toFriendlyInfo());
        synchronized (replicatedScheduling.getEventsFileInProgressLock()) {
          FailedEventUtil.moveFileToFailed(replicatedConfiguration, eventsFileBeingProcessed);
          replicatedScheduling.clearEventsFileInProgress(replicatedEventTask, false);
        }
        return;
      }

      logger.atWarning().log("RE Task [ %s ] has failures, have indicated to backoff project, for now (retry=1).",
          replicatedEventTask.toFriendlyInfo());

      // we didn't contain this skipped project info - as such lets just mark it to start the backoff.
      replicatedScheduling.addSkipThisProjectsEventsForNow(projectName);
      // now make sure we add this event into the skipped list - so we know its being skipped over,
      // and we dont schedule another file later on ahead of this one when the backoff period has expired.
      // Note this should be a prepend for a simple list, but we use an ordered queue to regardless if will
      // jump to the HEAD of the FIFO.
      replicatedScheduling.prependSkippedProjectEventFile(eventsFileBeingProcessed, projectName);
      return;
    }

    // otherwise we do have this failure in the skipped list, get the backoff info about this project.
    ProjectBackoffPeriod backoffPeriod = replicatedScheduling.getSkipThisProjectForNowBackoffInfo(projectName);

    // Check if we are on the last backoff retry - its special case of lets move to failed, unless DbIsStale then
    // we have to wait and keep retrying at max backoff ceiling.
    if (backoffPeriod.getNumFailureRetries() >= replicatedConfiguration.getMaxIndexBackoffRetries()) {
      if (isDBStale) { // perform checkPersistRemainingEntries
        // we can't increase the counter any more, but we still need to update the last start time we tried this.
        backoffPeriod.updateFailureInformation();
        logger.atWarning().log("RE Task [ %s ] has failures, it has been requested to move to failed directory, but will keep retrying as DB is currently stale. FailImmediately: %s BackoffInfo: %s",
            replicatedEventTask.toFriendlyInfo(), useFailImmediately, backoffPeriod);
        return;
      }

      logger.atWarning().log("RE Task [ %s ] has failures, it has been requested to move to failed directory, due to " +
              "max number of retries allowed: %s, or failImmediately: %s. Moving to failed.",
          replicatedEventTask.toFriendlyInfo(), backoffPeriod.getNumFailureRetries(), useFailImmediately);

      synchronized (replicatedScheduling.getEventsFileInProgressLock()) {
        FailedEventUtil.moveFileToFailed(replicatedConfiguration, eventsFileBeingProcessed);
        replicatedScheduling.clearEventsFileInProgress(replicatedEventTask, false);
        // we have failed this event file - lets free up future event files by removing this backoff lock.
        replicatedScheduling.clearSkipThisProjectsEventsForNow(projectName);
      }
      return;
    }

    if (useFailImmediately) {
      if (!isDBStale) {
        // we have been requested to fail now and we have processed all we can, lets put this file into failed with whatever
        // items are left.
        logger.atWarning().log("RE Task [ %s ] has failures, it has been requested to move to failed directory with failImmediately=true. Moving to failed.",
            replicatedEventTask.toFriendlyInfo());

        synchronized (replicatedScheduling.getEventsFileInProgressLock()) {
          FailedEventUtil.moveFileToFailed(replicatedConfiguration, eventsFileBeingProcessed);
          replicatedScheduling.clearEventsFileInProgress(replicatedEventTask, false);
          // we have failed this event file - lets free up future event files by removing this backoff lock.
          replicatedScheduling.clearSkipThisProjectsEventsForNow(projectName);
        }
        return;
      }

      // DbIsStale it takes prescedence over fail immediately - just let it continue and bump backoff counter.
      logger.atWarning().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS).log(
          "RE Task [ %s ] has failures, it has been requested to move to failed directory with failImmediately=true, but will keep retrying as DB is currently stale. NumFailureRetries: %s",
          replicatedEventTask.toFriendlyInfo(), backoffPeriod.getNumFailureRetries());
      // do not return here - let it fall through...
    }


    // ok we can attempt to fail this again.
    backoffPeriod.updateFailureInformation();
    logger.atWarning().log("RE Task [ %s ] has failures, updating backoff information: [ %s ].",
        replicatedEventTask.toFriendlyInfo(), backoffPeriod);
  }

  /**
   * As a result of a failure, we want to reduce the amount of retry work to only include the failed items, or items not
   * tried as appropriate.  We are handed a collection of items to be written to the existing file atomically.
   *
   * @param allEventsBeingProcessed  : All events for a given event file currently being processed.
   * @param correctlyProcessedEvents : Events that have succeeded already and don't need to be persisted.
   */
  private void checkPersistRemainingEntries(final ReplicatedEventTask replicatedEventTask,
                                            final List<EventWrapper> allEventsBeingProcessed,
                                            final List<EventWrapper> correctlyProcessedEvents) {

    if (allEventsBeingProcessed.size() == 0 || correctlyProcessedEvents.size() == 0
        || (allEventsBeingProcessed.size() == correctlyProcessedEvents.size())) {

      // if we have processed all events in the list, or we have processed none in the list we have nothing
      // to change within the events file.. only if we have done a subset are we to update the events file with
      // the remaining entries.  This case is here to skip over rewriting the file with the same contents
      // or no contents.
      return;
    }

    // Lets work out the remaining items to pass to the persister. We remove the events that have been correctly
    // processed from the list of all events being processed leaving only the events that have not been
    // correctly processed (which may include failed events).
    List<EventWrapper> remainingEvents = new LinkedList<>(allEventsBeingProcessed);
    remainingEvents.removeAll(correctlyProcessedEvents);

    try {
      FailedEventUtil.persistRemainingEvents(replicatedEventsCoordinator, replicatedEventTask, remainingEvents);
    } catch (IOException e) {
      logger.atSevere().log("Could not persist remaining events ", e.getMessage());
    }
  }

  private void attemptDeleteEventFile(File eventsFileBeingProcessed) {
    // We want to delete events files that have been published
    // as there is no need for them to linger in the incoming directory, specific jgit failures are requeued as
    // new events..
    // the only time we back off the entire file / group of events is for a custom exception which indicates
    // the DB is not up to date, or there was a json processing exception where we instead take this entire file and requeue.
    // everything else deletes the events file when finished processing.
    logger.atFine().log("Deleting event file %s", eventsFileBeingProcessed.getAbsolutePath());

    boolean deleted = eventsFileBeingProcessed.delete();
    if (!deleted) {
      logger.atSevere().log(
          "RE Could not delete file %s, this will cause a cyclic event processing loop if not resolved!",
          eventsFileBeingProcessed.getAbsolutePath());
    }
  }

  /**
   * Read the file supplied into a byte array stream, to handle various sizes.
   * It also abstracts away the notion of gzipped files, as the archive is dealt
   * with before the byte array stream is handed back to the caller.
   *
   * @param file, The event file to process
   * @return a ByteArrayOutputStream of the
   */
  public static ByteArrayOutputStream readFileToByteArrayOutputStream(File file, boolean incomingEventsAreGZipped) throws
      IOException {

    // ByteArrayOutputStream is an implementation of OutputStream that can write data into a byte array.
    // The buffer keeps growing as ByteArrayOutputStream writes data to it.
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         FileInputStream plainFileReader = new FileInputStream(file);
         // If the incoming events are Gzipped, then the reader will be a GZipInputStream otherwise
         // it will be a FileInputStream.
         InputStream reader = incomingEventsAreGZipped ? new GZIPInputStream(plainFileReader) : plainFileReader) {

      copyFile(reader, bos);
      return bos;
    }

  }


  /**
   * Reads from an InputStream into an OutputStream.
   *
   * @param source A InputStream which will either be a FileInputStream or a GZIPInputStream
   * @param dest   An OutputStream
   * @throws IOException if unable to read from either stream for any reason.
   */
  private static void copyFile(InputStream source, OutputStream dest)
      throws IOException {
    try (InputStream fis = source) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = fis.read(buf)) > 0) {
        dest.write(buf, 0, read);
      }
    }
  }
}
