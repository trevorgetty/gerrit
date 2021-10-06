package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.ReplicatedChangeTimeChecker;
import com.google.gerrit.common.replication.ConfigureReplication;
import com.google.gerrit.common.replication.IndexToReplicate;
import com.google.gerrit.common.replication.IndexToReplicateComparable;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsDBNotUpToDateException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gson.JsonSyntaxException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.util.Providers;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;

@Singleton
public class ReplicatedIncomingIndexEventProcessor extends GerritPublishableImpl {

  private static final Logger log = LoggerFactory.getLogger(ReplicatedIncomingIndexEventProcessor.class);

  private static ReplicatedIncomingIndexEventProcessor INSTANCE;

  private ReplicatedIncomingIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(INDEX_EVENT, eventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
  }

  //Get singleton instance
  public static ReplicatedIncomingIndexEventProcessor getInstance(ReplicatedEventsCoordinator eventsCoordinator) {
    if(INSTANCE == null) {
      INSTANCE = new ReplicatedIncomingIndexEventProcessor(eventsCoordinator);
      SingletonEnforcement.registerClass(ReplicatedIncomingIndexEventProcessor.class);
    }

    return INSTANCE;
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  /**
   * Called by the (gerrit) ReplicatedIncomingEventsWorker when it receives a replicated event of type INDEX_CHANGE
   * Puts the events in a queue which will be looked after by the IndexIncomingReplicatedEvents thread
   *
   * @param newEvent
   * @return success
   * @throws ReplicatedEventsDBNotUpToDateException
   */
  @Override
  public boolean publishIncomingReplicatedEvents(EventWrapper newEvent) {
    boolean success = false;

    if (newEvent != null) {
      switch (newEvent.getEventOrigin()) {
        case INDEX_EVENT:
          success = unwrapAndQueueAlreadyReplicatedIndexEvent(newEvent);
          break;
        default:
          log.error("RC INDEX_EVENT has been sent here but originator is not the right one ({})", newEvent.getEventOrigin());
      }
    } else {
      log.error("RC null event has been sent here");
    }
    return success;
  }

  private boolean unwrapAndQueueAlreadyReplicatedIndexEvent(EventWrapper newEvent) throws JsonSyntaxException {
    boolean success = false;
    try {
      Class<?> eventClass = Class.forName(newEvent.getClassName());
      IndexToReplicateComparable originalEvent = null;

      try {
        IndexToReplicate index = (IndexToReplicate) gson.fromJson(newEvent.getEvent(), eventClass);

        if (index == null) {
          log.error("fromJson method returned null for {}", newEvent.toString());
          return success;
        }

        originalEvent = new IndexToReplicateComparable(index, replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity());
      } catch (JsonSyntaxException je) {
        log.error("PR Could not decode json event {}", newEvent.toString(), je);
        return success;
      }

      log.debug("RC Received this event from replication: {}", originalEvent);

      // Lets do this actual index change now!
      return processIndexChangeCollection(originalEvent);
    } catch (ClassNotFoundException e) {
      log.error("RC INDEX_EVENT has been lost. Could not find {}", newEvent.getClassName(), e);
    }
    return false;
  }


  /**
   * Process a file full of events to be processed.
   * Returning true from this method means all events are processed without a failure.
   * Returning false means we have experienced a failure, and queue for retry if possible.
   *
   * finally failures that need to stop immediately without further processing of events in a file
   * with have thrown specific event exceptions such as ReviewDBNotUpToDat
   * @param indexEventToBeProcessed
   * @return
   */
  public boolean processIndexChangeCollection(IndexToReplicateComparable indexEventToBeProcessed) {
    NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges = new TreeMap<>();

    // make the list of changes a set of unique changes based only on the change number
    mapOfChanges.put(new Change.Id(indexEventToBeProcessed.indexNumber), indexEventToBeProcessed);

    if (mapOfChanges.isEmpty()) {
      return true;
    }

    return indexCollectionOfChanges(mapOfChanges);
  }

  /**
   * This will reindex changes in gerrit. Since it receives a map of changes (changeId -> IndexToReplicate) it will
   * try to understand if the index-to-replicate can be reindexed looking at the timestamp found for that ChangeId on
   * the database.
   * If it finds that the timestamp on the db is older than the one received from the replicator, then it will wait till the
   * db is updated. To compare the time we need to look at the Timezone of each modification since the sending gerrit can be
   * on a different timezone and the timestamp on the database reads differently depending on the database timezone.
   *
   * @param mapOfChanges
   */
  private boolean indexCollectionOfChanges(NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges) {
    try {
      Provider<ReviewDb> dbProvider = Providers.of(replicatedEventsCoordinator.getSchemaFactory().open());
      boolean failureExperienced = false;

      try (final ReviewDb db = dbProvider.get()) {

        for (IndexToReplicateComparable i : mapOfChanges.values()) {
          if (i.delete) {
            try {
              deleteChange(i.indexNumber);
            } catch (IOException e) {
              // we don't record deletions as failures, as we could of deleted on a first attempt and a later retry
              // might throw as its already been deleted?
              log.error("RC Error while trying to delete change index {}", i.indexNumber, e);
            }
          }
        }

        log.debug("RC Going to index {} changes...", mapOfChanges.size());

        // fetch changes from db
        long startTime = System.currentTimeMillis();


        // N.B : There is a subtle behaviour of the changes().get() method.
        // It only returns a collection of all *matching* entities;
        // this may be a smaller result than the keys supplied if one or more of the keys does not match an
        // existing entity. For example if we supply a keySet with a single entry that is NOT a matching entity then
        // changesOnDb will be 0. This could happen If for example Percona has not caught up yet on this site.
        //TODO Jira: This will need reworked as part of GER-1767
//        int numMatchingDbChanges = db.changes().get(mapOfChanges.keySet()).toList().size();
//        if(numMatchingDbChanges < mapOfChanges.size()){
//          log.debug("Number of matching changes found on the DB : {}", numMatchingDbChanges);
//          log.debug("Number of changes unaccounted for on the DB (That were not found in the matching changes) : {} "
//              , mapOfChanges.keySet().size());
//          throw new ReplicatedEventsDBNotUpToDateException(String.format("There were no matching changes found on the DB " +
//              "for the current change index ids being processed : %s", mapOfChanges.descendingKeySet()));
//        }

        // If the numMatchingDbChanges is not less than the number of change indexes in the map of changes
        // we're looking for (i.e all our changes have been found to exist on the DB) then set the changesOnDb variable
        ResultSet<Change> changesOnDb = db.changes().get(mapOfChanges.keySet());

        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime);
        log.debug("RC Time taken to fetch changes {}", duration);

        int totalDone = 0;
        int thisNodeTimeZoneOffset = IndexToReplicate.getRawOffset(System.currentTimeMillis());
        log.debug("thisNodeTimeZoneOffset={}", thisNodeTimeZoneOffset);
        // compare changes from db with the changes landed from the index change replication

        for (Change changeOnDb : changesOnDb) {
          try {
            // If the change on the db is old (last update has been done much before the one recorded in the change,
            // the put it back in the queue
            IndexToReplicateComparable indexToReplicate = mapOfChanges.get(changeOnDb.getId());
            ReplicatedChangeTimeChecker changeTimeChecker =
                new ReplicatedChangeTimeChecker(thisNodeTimeZoneOffset, changeOnDb, indexToReplicate, replicatedEventsCoordinator).invoke();
            boolean changeIndexedMoreThanXMinutesAgo = changeTimeChecker.isChangeIndexedMoreThanXMinutesAgo();
            Timestamp normalisedChangeTimestamp = changeTimeChecker.getNormalisedChangeTimestamp();
            Timestamp normalisedIndexToReplicate = changeTimeChecker.getNormalisedIndexToReplicate();

            log.debug("Comparing {} to {}. MoreThan is {}", normalisedChangeTimestamp, normalisedIndexToReplicate, changeIndexedMoreThanXMinutesAgo);

            // Note psuedo code of what is happening here.

            // db not up to date - throw now to requeue the entire block of events
            //                             ( entire file of events simply does not get deleted).
            // db is up to date....
            //    changeIndexedMoreThanXMinutesAgo < go ahead and process as normal
            //        if failure is jGitMissingObject ( persist just this event as a playable event file, in future +30secs
            //        if failure is generic, persist this event into failed directory (allow it to be replayed by simple move )
            //    changeIndexedMoreThanXMinutesAgo > even time
            //        process ONCE now.
            //        if failed -> keep in indicator of any failure regardless of why and
            //                if indicator=true throw exception at end of the full changes list so entire file
            //                is moved into failed directory ( same name for later reprocessing? )

            // dont reindex any events in this file - if DB is not up to date.
            if (normalisedChangeTimestamp.before(normalisedIndexToReplicate)) {
              log.info("Change {}, could not be processed yet, as the DB is not yet up to date.\n." +
                      "Push this entire group of changes back into the queue [db={}, index={}]",
                  indexToReplicate.indexNumber, changeOnDb.getLastUpdatedOn(), indexToReplicate.lastUpdatedOn);
              // Fail the entire group of events in this entire file
              throw new ReplicatedEventsDBNotUpToDateException("DB not up to date to deal with index: " + indexToReplicate.indexNumber);
            }

            // changeIndexedMoreThanXMinutesAgo: now keep in mind that this change may be stale - when it gets to the stale period, we still
            // allow it to be processed one more time - this allows for a server outage to allow all files to be processed
            // when we come alive.  but if it fails - put the entire set into failed directory !!
            try {
              replicatedEventsCoordinator.getChangeIndexer().indexNoRepl(db, changeOnDb.getProject(), changeOnDb.getId());
              log.debug("RC Change {} INDEXED!", changeOnDb.getChangeId());
              mapOfChanges.remove(changeOnDb.getId());
            } catch (Exception e) { // could be org.eclipse.jgit.errors.MissingObjectException
              log.warn(String.format("Got exception '%s' while trying to reindex change, will backoff this event file to retry later.", e.getMessage()), e);
              failureExperienced = true;

              if (e.getCause() instanceof org.eclipse.jgit.errors.MissingObjectException) {
                // retry this 30secs or so from now - but only this event, not the entire group!
                log.warn("Specific Change JGitMissingObject error noticed {} backoff this events file to retry later.", indexToReplicate.indexNumber);
              }

              // protect against edge case - low DB resolution where we could have A B C in same second,
              // but only A B is on Db yet. C has yet to come.
              else if (normalisedChangeTimestamp.equals(normalisedIndexToReplicate)) {
                // Special case if the DB is equal in seconds to this event time - please hold off the entire group and
                // try again just like DB not up to date exception.  Fail not dont process other events for this file as
                // it looks like DB is stale.
                log.warn("Specific Change error noticed {} pushed back in the queue", indexToReplicate.indexNumber);

                throw new ReplicatedEventsDBNotUpToDateException("DB equals same timestamp retry failure process events group.");
              }
            }
          }
          catch(ReplicatedEventsDBNotUpToDateException e){
            // this is a specific exception that we do not wish to catch and hide - we want to bubble this up
            // and ensure it is caught at higher level.. its stop the processing now, but also makes the retry not
            // move this event file into failed. It will increase the failure backoff period, but wont finally go over the max
            // retries forcing a move to delete until the DB is up to date and its tried once more.
            throw e;
          }
          catch (Exception e) {
            failureExperienced = true;
            log.error("RC Error while trying to reindex change {}, failed events will be retried later.", changeOnDb.getChangeId(), e);
          }
        }

        // Check for files that have remained too long and are no longer valid
        // because they are no longer found in the database
        // TREV Decide on when to delete the group of changes, it should really be back when the task is removed
        // from the pool that we then remove the events file - if too old!  If we fail we should throw a move to failed
        // and delete entire file?
        // TODO: trevorg
//      removeStaleIndexes(mapOfChanges);
        log.debug(String.format("RC Finished indexing %d changes... (%d) any failuresExperience=%s.",
            mapOfChanges.size(), totalDone, failureExperienced));
      }

      return !failureExperienced;
    } catch (OrmException e) {
      log.error("RC Error while trying to reindex change, unable to open the ReviewDB instance.", e);
      throw new ReplicatedEventsDBNotUpToDateException("RC Unable to open ReviewDB instance.");
    }
  }

  /**
   * Delete a list of changes
   *
   * @param changes Takes a list of changeIds
   * @throws IOException
   */
  public void deleteChanges(int[] changes) throws IOException {
    //iterate over the list of changes and delete each one
    for (int i : changes) {
      deleteChange(i);
    }
  }

  /**
   * Delete a list of changes
   *
   * @param changes Takes a list of ChangeData
   * @throws IOException
   */
  public void deleteChanges(List<ChangeData> changes) throws IOException {
    //iterate over the list of changes and delete each one
    for (ChangeData cd : changes) {
      deleteChange(cd.getId().id);
    }
  }

  /**
   * Delete a single change now on the system.  Synchronously used the gerrit Delete Task.
   *
   * @param indexNumber
   * @throws IOException
   */
  public void deleteChange(int indexNumber) throws IOException {
    replicatedEventsCoordinator.getChangeIndexer().delete(new Change.Id(indexNumber));
    log.info(("Deleted change: " + indexNumber));

  }

}
