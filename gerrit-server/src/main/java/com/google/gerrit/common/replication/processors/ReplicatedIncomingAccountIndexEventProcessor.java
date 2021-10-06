package com.google.gerrit.common.replication.processors;

import com.google.gerrit.common.AccountIndexEvent;
import com.google.gerrit.common.replication.SingletonEnforcement;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
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

  private static ReplicatedIncomingAccountIndexEventProcessor INSTANCE;

  private ReplicatedIncomingAccountIndexEventProcessor(ReplicatedEventsCoordinator eventsCoordinator) {
    super(ACCOUNT_INDEX_EVENT, eventsCoordinator);
    log.info("Creating main processor for event type: {}", eventType);
    subscribeEvent( this);
  }

  //Get singleton instance
  public static ReplicatedIncomingAccountIndexEventProcessor getInstance(ReplicatedEventsCoordinator eventsCoordinator) {
    if(INSTANCE == null) {
      INSTANCE = new ReplicatedIncomingAccountIndexEventProcessor(eventsCoordinator);
      SingletonEnforcement.registerClass(ReplicatedIncomingAccountIndexEventProcessor.class);
    }

    return INSTANCE;
  }


  @Override
  public void stop() {
    unsubscribeEvent( this);
  }

  public AccountIndexer getIndexer() {
    if(indexer == null){
      indexer = replicatedEventsCoordinator.getAccountIndexer();
    }
    return indexer;
  }

  @Override
  public boolean publishIncomingReplicatedEvents(
      EventWrapper newEvent) {
    boolean result = false;
    if (newEvent != null && newEvent.getEventOrigin() == EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      try {
        Class<?> eventClass = Class.forName(newEvent.getClassName());
        result = reindexAccount(newEvent, eventClass);
      } catch (ClassNotFoundException e) {
        log.error("RC AccountReIndex has been lost. Could not find {}", newEvent.getClassName(), e);
      }
    } else if (newEvent != null && newEvent.getEventOrigin() != EventWrapper.Originator.ACCOUNT_INDEX_EVENT) {
      log.error("RC AccountReIndex event has been sent here but originator is not the right one ({})", newEvent.getEventOrigin());
    }
    return result;
  }

  private boolean reindexAccount(EventWrapper newEvent, Class<?> eventClass) {
    boolean result = false;
    try {
      AccountIndexEvent originalEvent = (AccountIndexEvent) gson.fromJson(newEvent.getEvent(), eventClass);
      //Perform a local reindex.
      getIndexer().indexNoRepl(new Account.Id(originalEvent.indexNumber));
      return true;
    } catch (JsonSyntaxException je) {
      log.error("RC AccountReIndex, Could not decode json event {}", newEvent.toString(), je);
      return result;
    } catch (IOException ie) {
      log.error("RC AccountReindex issue hit while carrying out reindex of {}", newEvent.toString(), ie);
      return result;
    }
  }
}
