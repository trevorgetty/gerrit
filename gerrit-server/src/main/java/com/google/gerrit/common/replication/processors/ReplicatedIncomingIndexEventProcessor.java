package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.ReplicatedChangeTimeChecker;
import com.google.gerrit.common.replication.IndexToReplicate;
import com.google.gerrit.common.replication.IndexToReplicateComparable;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsDBNotUpToDateException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsMissingChangeInformationException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsTransientException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.INDEX_EVENT;

@Singleton
public class ReplicatedIncomingIndexEventProcessor extends GerritPublishableImpl {

  private static final Logger log = LoggerFactory.getLogger(ReplicatedIncomingIndexEventProcessor.class);

  /**
   * We only create this class from the replicatedEventscoordinator.
   * This is a singleton and its enforced by our SingletonEnforcement below that if anyone else tries to create
   * this class it will fail.
   * Sorry by adding a getInstance, make this class look much more public than it is,
   * and people expect they can just call getInstance - when in fact they should always request it via the
   * ReplicatedEventsCordinator.getReplicatedXWorker() methods.
   *
   * @param eventsCoordinator
   */
  public ReplicatedIncomingIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(INDEX_EVENT, eventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingIndexEventProcessor.class);
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
  public void publishIncomingReplicatedEvents(EventWrapper newEvent) {
    switch (newEvent.getEventOrigin()) {
      case INDEX_EVENT:
        unwrapAndQueueAlreadyReplicatedIndexEvent(newEvent);
        break;
      default:
        log.error("RC INDEX_EVENT has been sent here but originator is not the right one ({})", newEvent.getEventOrigin());
    }
  }

  private void unwrapAndQueueAlreadyReplicatedIndexEvent(EventWrapper newEvent) throws JsonSyntaxException {

    try {
      Class<?> eventClass = Class.forName(newEvent.getClassName());
      IndexToReplicate index = (IndexToReplicate) gson.fromJson(newEvent.getEvent(), eventClass);

      if (index == null) {
        throw new JsonSyntaxException("Event Json Parsing returning no valid event information from: " + newEvent);
      }

      IndexToReplicateComparable originalEvent = new IndexToReplicateComparable(index, replicatedEventsCoordinator.getReplicatedConfiguration().getThisNodeIdentity());
      log.debug("RC Received this event from replication: {}", originalEvent);

      // Lets do this actual index change now!
      processIndexChangeCollection(originalEvent);
      return;
    } catch (JsonSyntaxException je) {
      log.error("PR Could not decode json event {}", newEvent, je);
      throw new JsonSyntaxException(String.format("PR Could not decode json event %s", newEvent), je);
    } catch (ClassNotFoundException e) {
      final String err = String.format("WARNING: Unable to publish a replicated INDEX_EVENT using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
      log.warn(err);
      throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
    }
  }


  /**
   * Process a file full of events to be processed.
   * Returning true from this method means all events are processed without a failure.
   * Returning false means we have experienced a failure, and queue for retry if possible.
   * <p>
   * finally failures that need to stop immediately without further processing of events in a file
   * with have thrown specific event exceptions such as ReviewDBNotUpToDat
   *
   * @param indexEventToBeProcessed
   * @return
   */
  public void processIndexChangeCollection(IndexToReplicateComparable indexEventToBeProcessed) {
    NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges = new TreeMap<>();

    // make the list of changes a set of unique changes based only on the change number
    // Note this is a replace last item matching this ID, so if 2 index change (id=3) come in only
    // the last one will be in this list matching key=value. e.g.(id=3=<last>)
    mapOfChanges.put(new Change.Id(indexEventToBeProcessed.indexNumber), indexEventToBeProcessed);

    if (mapOfChanges.isEmpty()) {
      return;
    }

    indexCollectionOfChanges(mapOfChanges);
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
  private void indexCollectionOfChanges(NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges) {
    try {
      Provider<ReviewDb> dbProvider = Providers.of(replicatedEventsCoordinator.getSchemaFactory().open());
      Collection<Integer> deletedIdsList = new HashSet<>(); // using a set so we have each id only once.

      try (final ReviewDb db = dbProvider.get()) {

        Iterator<Map.Entry<Change.Id, IndexToReplicateComparable>> iter = mapOfChanges.entrySet().iterator();
        while(iter.hasNext()){
          Map.Entry<Change.Id, IndexToReplicateComparable> item = iter.next();
          IndexToReplicateComparable i = item.getValue();
          if (i.delete) {
            try {
              deleteChange(i.indexNumber);
              deletedIdsList.add(i.indexNumber);
            } catch (IOException e) {
              // we don't record deletions as failures, as we could of deleted on a first attempt and a later retry
              // might throw as its already been deleted?
              log.error("RC Error while trying to delete change index {}", i.indexNumber, e);
            }
            iter.remove();
          }
        }

        if ( mapOfChanges.isEmpty() ){
          // all changes were deletes - lets exit now.
          log.debug("RC All Events being processed where deletions - nothing more to do.");
          return;
        }

        // We cannot be requesting index changes for items that have been deleted - lets
        log.debug("RC Going to index {} changes...", mapOfChanges.size());

        // fetch changes from db
        long startTime = System.currentTimeMillis();

        // N.B : There is a subtle behaviour of the changes().get() method.
        // It only returns a collection of all *matching* entities;
        // this may be a smaller result than the keys supplied if one or more of the keys does not match an
        // existing entity. For example if we supply a keySet with a single entry that is NOT a matching entity then
        // changesOnDb will be 0. This could happen If for example Percona has not caught up yet on this site.
        // Or if the item has been deleted already by a deletion event.
        // reworked as part of GER-1767
        List<Change> changesList;

        {
          final ResultSet<Change> changesOnDb = db.changes().get(mapOfChanges.keySet());
          changesList = changesOnDb.toList();
        }

        final int numMatchingDbChanges = changesList.size();
        if (numMatchingDbChanges < mapOfChanges.size()) {
          // Lets work out the missing ids, and report them in the exception only.
          Collection<Integer> listOfFoundIds = buildListFromChange(changesList);
          Collection<Integer> listOfRequestedIds = buildListFromChangeId(mapOfChanges);

          // populate a simple Ids list for quick manipulation.
          // add all requested ids, then remove the founds ones.
          Collection<Integer> listOfMissingIds = buildListOfMissingIds(listOfRequestedIds, listOfFoundIds);

          // Lets check before failing, do any of the missing Ids have a safe to ignore missing flag, if so we can
          // ignore this safely without reporting it as a failure / backoff event.
          if ( areAllMissingItemsSafeToIgnore(mapOfChanges, listOfMissingIds) ){
            // we have found all missing items are safe to be ignored - lets exit.
            log.info("Safe to ignore already deleted INDEX_EVENT(s): {}", listOfMissingIds);
            return;
          }

          log.warn("Number of matching changes found on the DB : {} doesn't match requested num changes: {}",
              numMatchingDbChanges, mapOfChanges.size());

          // now we have the full picture - of requested, found, and missing lets raise exception
          // with the values of missing changes...
          throw new ReplicatedEventsMissingChangeInformationException(String.format(
              "There were no matching changes found on the DB for the following changeIds: %s.  " +
                  "Requested numChanges: %s but found: %s. and  current change index ids being processed : %s",
              listOfMissingIds, listOfRequestedIds.size(), listOfFoundIds.size(), mapOfChanges.descendingKeySet()));
        }

        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime);
        log.debug("RC Time taken to fetch changes {}", duration);

        int totalDone = 0;
        int thisNodeTimeZoneOffset = IndexToReplicate.getRawOffset(System.currentTimeMillis());
        log.debug("thisNodeTimeZoneOffset={}", thisNodeTimeZoneOffset);
        // compare changes from db with the changes landed from the index change replication

        //  N.B future enhancement. indexCollectionOfChanges will only ever receive a mapOfChanges of size
        //  1. Therefore we wouldn't need the iteration on changesOnDb here or elsewhere throughout this method.
        //  a future enhancement would be to create a map of index events per eventFile and figure out how to
        //  pass multiple failures back to the caller. For now we only process one event at a time.
        for (Change changeOnDb : changesList) {
          try {
            // If the change on the db is old (last update has been done much before the one recorded in the change,
            // the put it back in the queue
            IndexToReplicateComparable indexToReplicate = mapOfChanges.get(changeOnDb.getId());
            ReplicatedChangeTimeChecker changeTimeChecker =
                new ReplicatedChangeTimeChecker(thisNodeTimeZoneOffset, changeOnDb, indexToReplicate, replicatedEventsCoordinator).invoke();

            // check the DB row is not behind this event info.
            checkDBChangeRowTimestamp(changeOnDb, indexToReplicate, changeTimeChecker);

            // changeIndexedMoreThanXMinutesAgo: now keep in mind that this change may be stale - when it gets to the stale period, we still
            // allow it to be processed one more time - this allows for a server outage to allow all files to be processed
            // when we come alive.  but if it fails - put the entire set into failed directory !!
            try {
              replicatedEventsCoordinator.getChangeIndexer().indexNoRepl(db, changeOnDb.getProject(), changeOnDb.getId());
              log.debug("RC Change {} INDEXED!", changeOnDb.getChangeId());
              mapOfChanges.remove(changeOnDb.getId());
            } catch (Exception e) { // could be org.eclipse.jgit.errors.MissingObjectException
              log.warn(String.format("Got exception '%s' while trying to reindex change, will backoff this event file to retry later.", e.getMessage()), e);

              // just before we indicate a failure - if we have a deletion on the same change earlier on, lets
              // ignore this!!
              if (deletedIdsList.contains(changeOnDb.getChangeId())) {
                // we have a failure but we already deleted this id before now... ignore it
                log.warn("Ignoring change failure: {} - as we have already deleted this change before now.", changeOnDb.getChangeId());
                continue;
              }

              if (e.getCause() instanceof org.eclipse.jgit.errors.MissingObjectException) {
                // retry this 30secs or so from now - but only this event, not the entire group!
                log.warn("Specific Change JGitMissingObject error noticed {} backoff this events file to retry later.", indexToReplicate.indexNumber);
              }

              // protect against edge case - low DB resolution where we could have A B C in same second,
              // but only A B is on Db yet. C has yet to come.
              else if (changeTimeChecker.isTimeStampEqual()) {
                // Special case if the DB is equal in seconds the change is there and it should have been a no-op.
                // This should have passed, is there somehow a way that an event change row can be updated without
                // updating the lastUpdatedOn time held for that row by the DB?? Lets treat as if we didn't get the row
                // at all, so lets delete all working events before this one, and let it replay later.
                log.warn("Specific Change error noticed {} with matching lastUpdatedOn time - pushed back in the queue", indexToReplicate.indexNumber);

                throw new ReplicatedEventsMissingChangeInformationException("DB equals same timestamp retry failure process events group.");
              }

              final String err = String.format("RC Error while trying to reindex change %s, failed events will be retried later.", changeOnDb.getChangeId());
              log.error(err, e);
              throw new ReplicatedEventsTransientException(err);
            }
          } catch (ReplicatedEventsDBNotUpToDateException | ReplicatedEventsMissingChangeInformationException e) {
            // this is a specific exception that we do not wish to catch and hide - we want to bubble this up
            // and ensure it is caught at higher level.. its stop the processing now, but also makes the retry not
            // move this event file into failed. It will increase the failure backoff period, but wont finally go over the max
            // retries forcing a move to delete until the DB is up to date and its tried once more.
            throw e;
          } catch (Exception e) {
            final String err = String.format("RC Error while trying to reindex change %s, failed events will be retried later.", changeOnDb.getChangeId());
            log.error(err, e);
            throw new ReplicatedEventsTransientException(err);
          }
        }

        log.debug(String.format("RC Finished indexing %d changes... (%d).", mapOfChanges.size(), totalDone));
      }
    } catch (OrmException e) {
      log.error("RC Error while trying to reindex change, unable to open the ReviewDB instance.", e);
      throw new ReplicatedEventsDBNotUpToDateException("RC Unable to open ReviewDB instance.");
    }
  }

  /**
   * Simple check if all the missing items - were safe to be ignored currently.
   * @param mapOfChanges
   * @param listOfMissingIds
   * @return
   */
  private boolean areAllMissingItemsSafeToIgnore(final NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges,
                                                 final Collection<Integer> listOfMissingIds) {
    for ( int missingItem : listOfMissingIds ){
      IndexToReplicateComparable index = mapOfChanges.get(new Change.Id(missingItem));
      if ( index == null || !index.safeToIgnoreMissingChange ){
        // we have found that this missing item, can't be found, or it can not be ignored.
        // Either way ALL items can't be verified as safe to ignore, so exit early with FALSE.
        // we can exit early!
        return false;
      }
    }
    return true;
  }

  public static Collection<Integer> buildListOfMissingIds(final Collection<Integer> listOfRequestedIds, final Collection<Integer> listOfFoundIds) {
    Collection<Integer> listOfMissingIds = new HashSet<>(listOfRequestedIds);
    listOfMissingIds.removeAll(listOfFoundIds);
    return listOfMissingIds;
  }

  private Collection<Integer> buildListFromChangeId(NavigableMap<Change.Id, IndexToReplicateComparable> mapOfChanges) {
    Collection<Integer> listOfIds = new HashSet<>();

    for (Change.Id requestedChangeId : mapOfChanges.keySet()) {
      // just check do we have a match in our changesOnDb list?
      // Would be nicer to have a stream - but in j7 here just nested FOR it, the lists are very small
      // usually 1-10 items, no real benefit of rework int
      listOfIds.add(requestedChangeId.id);
    }
    return listOfIds;
  }

  private Collection<Integer> buildListFromChange(final List<Change> changesOnDb) {
    Collection<Integer> listOfIds = new HashSet<>();

    for (Change foundChange : changesOnDb) {
      listOfIds.add(foundChange.getChangeId());
    }
    return listOfIds;
  }

  /**
   * We want to check that the row in the DB is the same as the event timestamp we replicated or newer.
   * We also need to account for DB timezone information.
   *
   * @param changeOnDb
   * @param indexToReplicate
   * @param changeTimeChecker
   */
  private void checkDBChangeRowTimestamp(Change changeOnDb, IndexToReplicateComparable indexToReplicate, ReplicatedChangeTimeChecker changeTimeChecker) {
    boolean changeIndexedMoreThanXMinutesAgo = changeTimeChecker.isChangeIndexedMoreThanXMinutesAgo();
    Timestamp normalisedChangeTimestamp = changeTimeChecker.getNormalisedChangeTimestamp();
    Timestamp normalisedIndexToReplicate = changeTimeChecker.getNormalisedIndexToReplicate();

    log.debug("Comparing changeTimestamp={} to indexToReplicate={}. ChangedIndexedMoreThanXMinuteAgo is {}",
        normalisedChangeTimestamp, normalisedIndexToReplicate, changeIndexedMoreThanXMinutesAgo);

    // db not up to date - throw now to requeue the entire block of events
    //                             ( entire file of events simply does not get deleted).
    // db is up to date....
    //    changeIndexedMoreThanXMinutesAgo < go ahead and process as normal
    //        if failure is jGitMissingObject ( persist just this event as a playable event file, in future +30secs
    //        if failure is generic, persist this event into failed directory (allow it to be replayed by simple move )
    //    changeIndexedMoreThanXMinutesAgo > event time
    //        process ONCE now.
    //        if failed -> keep in indicator of any failure regardless of why and
    //                if indicator=true throw exception at end of the full changes list so entire file
    //                is moved into failed directory ( same name for later reprocessing? )

    // dont reindex any events in this file - if DB is not up to date.
    if (changeTimeChecker.isTimeStampBefore()) {
      log.info("Change {}, could not be processed yet, as the DB is not yet up to date." +
              "Push this entire group of changes back into the queue [db={}, index={}]",
          indexToReplicate.indexNumber, changeOnDb.getLastUpdatedOn(), indexToReplicate.lastUpdatedOn);
      // Fail the entire group of events in this entire file
      throw new ReplicatedEventsDBNotUpToDateException("DB not up to date to deal with index: " + indexToReplicate.indexNumber);
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
