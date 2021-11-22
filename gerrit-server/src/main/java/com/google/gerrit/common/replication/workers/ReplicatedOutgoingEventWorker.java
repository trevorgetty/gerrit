package com.google.gerrit.common.replication.workers;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.replication.PersistedEventInformation;
import com.google.gerrit.common.replication.ReplicatedConfiguration;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class ReplicatedOutgoingEventWorker implements Runnable{
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Queue of events to replicate, note the queue is held here so that the we can add to the queue from feeders,
  // and read from the queue to write to disk from the Workers.
  public final ConcurrentLinkedQueue<EventWrapper> queue =
      new ConcurrentLinkedQueue<>();

  private Map<String, PersistedEventInformation> persistedEventInformationMap = new LinkedHashMap<>();


  private ReplicatedEventsCoordinator replicatedEventsCoordinator;
  private ReplicatedConfiguration replicatedConfiguration;
  private final Gson gson;

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   * @param replicatedEventsCoordinator
   */
  public ReplicatedOutgoingEventWorker(ReplicatedEventsCoordinator replicatedEventsCoordinator) {
        // for ease of use cache this class handle - its singleton anyway.
    this.replicatedEventsCoordinator = replicatedEventsCoordinator;
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
    this.gson = replicatedEventsCoordinator.getGson();
    SingletonEnforcement.registerClass(ReplicatedOutgoingEventWorker.class);
  }

  public void queueEventWithOutgoingWorker(EventWrapper event) {
    if (!queue.offer(event)) {
      logger.atSevere().log("Unable to offer event to the outgoing worker queue.");// queue is unbound, no need to check for result
    }
  }


  /**
   * Main thread which will poll for events in the queue, events which are published by Gerrit, and
   * will save them to files. When enough (customizable) time has passed or when enough (customizable)
   * events have been saved to a file, this will be renamed with a pattern that will be taken care of
   * by the GitMS replicator and then deleted.
   *
   * We process the events on the queue on a per project basis now i.e events are written
   * to event files on a per project basis.
   * We now keep a map (outgoingEventInformationMap) which records the following outgoing event information
   * - Map<project name, PersistedEventInformation>
   *   {@link PersistedEventInformation}
   *   The PersistedEventInformation records the following about an outgoing event:
   *   <ul>
   *     <li>the project .tmp event file being written to</li>
   *     <li>The FileOutputStream which will write the .tmp event file.</li>
   *     <li>The final name to atomic rename the .tmp file to</li>
   *   </ul>
   *
   * N.B if no items in queue - doesn't mean we have nothing to do, we also need
   * to look at outgoingEventInformationMap for contents.
   *
   * We process the queue and add to the outgoingEventInformationMap from this queue.
   *
   * Example. Say we have the following items in queue
   * ProjectA - new file (add to map, record new file, and EventWrapperTime(time on file))
   * ProjectA - same file ( already map, already has a file - append.)
   * ProjectB - new file ( add_to_map, record new file, and EventWrapperTime)
   * ProjectA - same file ( already_map ....., append)
   *
   * when all events have been polled on the queue and processed, check now if there are any ready to be sent...
   * The logic for checkSendEventFiles
   * {@link ReplicatedOutgoingEventWorker#checkSendEventFiles()} ()} () checkSendEventFiles}
   * for( project : map ){
   *  if NumEvents >= maxNumberOfEventsBeforeProposing - send it and remove from map.
   *  if TimeSinceFirstEvent >= maxSecsToWaitBeforeProposingEvents  - send it and remove from map.
   * }
   *
   * So we may send none, some or all of the items(project files) in the map!
   * Go to sleep / or be called again Xms from now. (getMaxSecsToWaitOnPollAndRead)
   *
   * */
  @Override
  public void run() {
    try{
      logger.atFinest().log("Polling the queue for outgoing events to write");
      pollAndWriteOutgoingEvents();
      // No events in the queue to poll.
      // Check if we have events in the map that are ready to send
      checkSendEventFiles();
      logger.atFinest().log("Finished pollAndWriteOutgoingEvents()");
    } catch (RuntimeException ex) {
      logger.atSevere().withCause(ex).log();
    }
    catch (Throwable t) {
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
   * poll for the events published by gerrit and send to the other nodes through
   * files read by the replicator
   */
  private void pollAndWriteOutgoingEvents() {

    if (!replicatedConfiguration.getOutgoingReplEventsDirectory().exists()) {
      logger.atSevere().atMostEvery(replicatedConfiguration.getLoggingMaxPeriodValueMs(), TimeUnit.MILLISECONDS).log(
          "Outgoing replicated events directory [ %s ] cannot be found. Replicated events will not work!",
          replicatedConfiguration.getOutgoingReplEventsDirectory().getAbsolutePath());
      return;
    }

    EventWrapper newEvent;
    while ((newEvent = queue.poll()) != null) {
      try {
        final String projectName = newEvent.getProjectName();

        // If the project is new, i.e it is not in the map then create a new
        // event file for it which will be stored off in PersistedEventInformation.
        if ( ! persistedEventInformationMap.containsKey(projectName) ) {
          setNewCurrentEventsFile(projectName, newEvent);
        }

        //The project exists in the map already so append the event bytes to the file
        if(! persistedEventInformationMap.get(projectName).appendToFile(newEvent, true)){
          logger.atSevere().log("Could not append event  [ %s ] to existing file", newEvent.getEvent());
        }

      } catch (IOException e) {
        logger.atSevere().withCause(e).log("RE Cannot create buffer file for events queueing!", e);
      }
    }
  }


  /**
   * Check if we have entries in the outgoingEventInformationMap that need to be sent as
   * event files. Entries are on a per project basis
   * If we have a map of the following:
   * <ul>
   *   <li>{ProjectA, PersistedEventInformation}</li>
   *   <li>{ProjectB, PersistedEventInformation}</li>
   *   <li>{ProjectC, PersistedEventInformation}</li>
   * </ul>
   * We will iterate over each of these entries. The PersistedEventInformation keeps track of the .tmp
   * file where the events per project were written to as well as the final event file name which the .tmp
   * file is atomically renamed to.
   * {@link PersistedEventInformation}
   *
   * If NumEvents >= maxNumberOfEventsBeforeProposing
   * OR
   * If TimeSinceFirstEvent >= maxSecsToWaitBeforeProposingEvents
   *
   * then we call {@link PersistedEventInformation#setFileReady()} if we have events that were written
   * to the per project event file. The .tmp file for the events is then atomically renamed
   * {@link PersistedEventInformation#atomicRenameTmpFilename()} ()}
   *
   * finally we call iter.remove() to remove the instance from the map. This is done in both cases where
   * we have no events in the event file and where we have written events and then atomically renamed the file.
   */
  private void checkSendEventFiles() {

    Iterator<Map.Entry<String, PersistedEventInformation>> iter = persistedEventInformationMap.entrySet().iterator();
    while (iter.hasNext()) {

      Map.Entry<String, PersistedEventInformation> entry = iter.next();
      PersistedEventInformation persistedEventInformation = entry.getValue();

      logger.atFinest().log("Entry is for projectName [ %s ], .tmp file [ %s ]", entry.getValue().getProjectName(),
          entry.getValue().getEventFile());

      // If NumEvents >= maxNumberOfEventsBeforeProposing - send it and remove from map. OR
      // If TimeSinceFirstEvent >= maxSecsToWaitBeforeProposingEvents  - send it and remove from map.
      if (persistedEventInformation.exceedsMaxEventsBeforeProposing()
          || persistedEventInformation.timeToWaitBeforeProposingExpired()) {

        //Check we have something to write to disk, then do atomic rename now, otherwise just remove from the map.
        if(persistedEventInformation.setFileReady()){
          // Then do atomic rename of the .tmp file to its final event file name.
          if ( persistedEventInformation.atomicRenameTmpFilename() )
          {
            // The rename was successful
            logger.atInfo().log("RE Created new file [ %s ] for project [ %s ] to be proposed",
                persistedEventInformation.getFinalEventFileName(), persistedEventInformation.getProjectName() );
          }
        }
        //then remove entry from the map
        iter.remove();

        //If we still have events to add to the events file, then because we have removed the entry from
        //the map for the project, a new entry will be created and a new events file will be created on disk
        //for the continuing events for the same project
        //For example: say we had the following scenario
        //  - We are polling the event queue,
        //  - We poll an event for ProjectA, ProjectA is not in our map so we create a Map entry
        //  - ProjectA - new event file created
        //  - We continue polling events for ProjectA until either
        //    exceedsMaxEventsBeforeProposing() or timeToWaitBeforeProposingExpired(). If we evaluate true for
        //    either of these then iter.remove() will be called removing ProjectA from the map.
        //  - But there are more events to be polled for ProjectA in the queue.
        //  - ProjectA - new event file created
        //    and so on.
      }
    }
  }



  /**
   * Write out to event file based on the project name...
   *
   * Logic is to hold a file per project while still queueing events for a given project.
   * if the project is new, create file.
   *   Otherwise keep appending to existing file
   * Note eventually based on time or num events we will send this file.
   */
  private void setNewCurrentEventsFile(String projectName, final EventWrapper originalEvent) {
    // Put an entry in the map for this projectName and associate it with a PersistedEventInformation instance
    // That holds the .tmp file and the writer for it. It also knows about the final event file name.
    try {
      persistedEventInformationMap.put(projectName,
          new PersistedEventInformation(replicatedEventsCoordinator, originalEvent));
    } catch (IOException e) {
      logger.atSevere().log("Could not add event for %s to the map to track, %s",
          projectName, e.getMessage());
    }
  }
}
