package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsImmediateFailWithoutBackoffException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsTransientException;
import com.google.gerrit.common.replication.exceptions.ReplicatedEventsUnknownTypeException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Singleton;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.wandisco.gerrit.gitms.shared.events.EventWrapper.Originator.ACCOUNT_INDEX_EVENT;

@Singleton //Not guice bound but makes it clear that its a singleton
public class ReplicatedIncomingAccountIndexEventProcessor extends GerritPublishableImpl {
  private static final Logger log = LoggerFactory.getLogger(
      ReplicatedIncomingAccountIndexEventProcessor.class);

  private AccountIndexer indexer;

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
  public ReplicatedIncomingAccountIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(ACCOUNT_INDEX_EVENT, eventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent(this);
    SingletonEnforcement.registerClass(ReplicatedIncomingAccountIndexEventProcessor.class);
  }

  @Override
  public void stop() {
    unsubscribeEvent(this);
  }

  public AccountIndexer getIndexer() {
    if (indexer == null) {
      indexer = replicatedEventsCoordinator.getAccountIndexer();
    }
    return indexer;
  }

  @Override
  public void publishIncomingReplicatedEvents(EventWrapper newEvent) {

    if (newEvent.getEventOrigin() == EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        reindexAccount(newEvent, eventClass);
        return;
      } catch (ClassNotFoundException e) {
        final String err = String.format("WARNING: Unable to publish a replicated event using Class: %s : Message: %s", e.getClass().getName(), e.getMessage());
        log.warn(err);
        throw new ReplicatedEventsImmediateFailWithoutBackoffException(err);
      }
    }

    final String err = String.format("Event has been sent to ACCOUNT_INDEX_EVENT processor but originator is not the right one (%s)", newEvent);
    log.error(err);
    throw new ReplicatedEventsUnknownTypeException(err);
  }

  private void reindexAccount(EventWrapper newEvent, Class<?> eventClass) {
    try {
      AccountIndexEvent originalEvent = (AccountIndexEvent) gson.fromJson(newEvent.getEvent(), eventClass);
      if (originalEvent == null) {
        throw new JsonSyntaxException("Event Json Parsing returning no valid event information from: " + newEvent);
      }
      //Perform a local reindex.
      getIndexer().indexNoRepl(new Account.Id(originalEvent.indexNumber));
      return;
    } catch (JsonSyntaxException je) {
      log.error("PR Could not decode json event {}", newEvent, je);
      throw new JsonSyntaxException(String.format("PR Could not decode json event %s", newEvent), je);
    } catch (IOException ie) {
      final String err = String.format("RC AccountReindex issue hit while carrying out reindex of %s", newEvent);
      log.error(err, ie);
      throw new ReplicatedEventsTransientException(err, ie);
    }
  }
}
