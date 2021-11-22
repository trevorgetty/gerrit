package com.google.gerrit.common.replication;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.replication.coordinators.ReplicatedEventsCoordinator;
import com.google.gson.Gson;
import com.wandisco.gerrit.gitms.shared.events.EventWrapper;
import com.wandisco.gerrit.gitms.shared.events.GerritEventData;
import com.wandisco.gerrit.gitms.shared.util.ObjectUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.gerrit.common.replication.ReplicationConstants.DEFAULT_NANO;
import static com.google.gerrit.common.replication.ReplicationConstants.ENC;
import static com.google.gerrit.common.replication.ReplicationConstants.NEXT_EVENTS_FILE;
import static com.wandisco.gerrit.gitms.shared.util.StringUtils.getProjectNameSha1;

public class PersistedEventInformation {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final long firstEventTime;
  private final File eventFile;
  private String finalEventFileName;
  private final FileOutputStream fileOutputStream;
  private boolean isFileOutputStreamClosed = false;
  private AtomicInteger numEventsWritten = new AtomicInteger(0);
  private ReplicatedConfiguration replicatedConfiguration;
  private final String projectName;
  private final File outputDirectory;
  private final Gson gson;


  public PersistedEventInformation(final ReplicatedEventsCoordinator replicatedEventsCoordinator, EventWrapper originalEvent) throws IOException {
    this.firstEventTime = System.currentTimeMillis();
    //Project key doesn't exist in the map so create a new file
    //Create a file with a filename composed of the contents of the event file itself.
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
    this.gson = replicatedEventsCoordinator.getGson();
    this.outputDirectory = replicatedConfiguration.getOutgoingReplEventsDirectory();
    this.eventFile = getTempEventFile();
    this.finalEventFileName = createThisEventsFilename(originalEvent);
    this.fileOutputStream = new FileOutputStream(eventFile);
    this.projectName = originalEvent.getProjectName();
  }

  //This is for updating an existing event file with new information
  public PersistedEventInformation(final ReplicatedEventsCoordinator replicatedEventsCoordinator,
                                   String originalEventFileName, String projectName) throws IOException {
    this.firstEventTime = System.currentTimeMillis();
    //Project key doesn't exist in the map so create a new file
    //Create a file with a filename composed of the contents of the event file itself.
    this.replicatedConfiguration = replicatedEventsCoordinator.getReplicatedConfiguration();
    this.gson = replicatedEventsCoordinator.getGson();
    this.outputDirectory = replicatedConfiguration.getIncomingReplEventsDirectory();
    this.eventFile = getTempEventFile();
    this.finalEventFileName = originalEventFileName;
    this.fileOutputStream = new FileOutputStream(eventFile);
    this.projectName = projectName;
  }


  public String getProjectName() {
    return projectName;
  }

  public long getFirstEventTime() {
    return firstEventTime;
  }

  public FileOutputStream getFileOutputStream() {
    return fileOutputStream;
  }

  public File getEventFile() {
    return eventFile;
  }

  public AtomicInteger getNumEventsWritten() {
    return numEventsWritten;
  }

  public boolean isFileOutputStreamClosed() {
    return isFileOutputStreamClosed;
  }

  public void setFileOutputStreamClosed(boolean fileOutputStreamClosed) {
    isFileOutputStreamClosed = fileOutputStreamClosed;
  }


  public String getFinalEventFileName() {
    return finalEventFileName;
  }

  /* Can be used for testing */
  public void setFinalEventFileName(String finalEventFileName) {
    this.finalEventFileName = finalEventFileName;
  }


  /**
   * Utility method to get a .tmp event file name.
   * @return filename of the format events-<randomuuid>.tmp
   * @throws IOException
   */
  private File getTempEventFile() throws IOException {
    return File.createTempFile("events-", ".tmp", outputDirectory);
  }

  /**
   * Closes the FileOutputStream (if open) associated with the .tmp event file
   * performs an FD.sync if isSyncFiles is set/
   * @return true if FileOutputStream successfully closed and FD sync completed.
   */
  public boolean setFileReady() {
    if (numEventsWritten.get() == 0) {
      logger.atFine().log("RE No events to send. Waiting...");
      return false;
    }

    logger.atFine().log("RE Closing file and renaming to be picked up");
    try {
      if(!isFileOutputStreamClosed()) {
        getFileOutputStream().close();
        setFileOutputStreamClosed(true);
      }
    } catch (IOException ex) {
      logger.atWarning().withCause(ex).log("RE unable to close the file to send");
    }

    if (replicatedConfiguration.isSyncFiles()) {
      try {
        getFileOutputStream().getFD().sync();
      } catch (IOException ex) {
        logger.atWarning().withCause(ex).log("RE unable to sync the file to send");
      }
    }
    return true;
  }


  /**
   * This is the only method writing events to the event files.
   * write the events-<randomnum>.tmp file, increase the NumEventsWritten and
   * @param projectName : The name of the project that we are writing events for
   * @param bytes : The bytes for EventWrapper instance
   * @return true if bytes written successfully.
   */
  public boolean writeEventsToFile(final String projectName, byte[] bytes) {
    try{
      if(getFileOutputStream() != null) {

        getFileOutputStream().write(bytes);
        //An event has been written, increment the projectEvent numEvents counter.
        getNumEventsWritten().incrementAndGet();

        logger.atFine().log("Number of events written to the events file [ %s ] for " +
                "project [ %s ] is currently : [ %s ].", eventFile, projectName, getNumEventsWritten().get() );
      }

    } catch (IOException e) {
      logger.atSevere().log("Unable to write the JSON event bytes to [ %s ] :", e.getMessage());
      return false;
    }
    return true;
  }


  /**
   * This will create append to the current file the last event received. If the
   * project name of the this event is different from the the last one, then we
   * need to create a new file anyway, because we want to pack events in one
   * file only if the are for the same project
   *
   * @param originalEvent : The EventWrapper instance that was polled from the queue.
   * @return true if the event was successfully appended to the file
   * @throws IOException
   */
  public boolean appendToFile(final EventWrapper originalEvent, boolean updateMetrics)
      throws IOException {

    // If the project is the same, write the file
    final String wrappedEvent = gson.toJson(originalEvent) + '\n';
    byte[] bytes = wrappedEvent.getBytes(ENC);

    logger.atFine().log("RE Last json to be sent: %s", wrappedEvent);
    if(updateMetrics) {
      ReplicatorMetrics.totalPublishedLocalEvents.incrementAndGet();
      ReplicatorMetrics.totalPublishedLocalEventsBytes.addAndGet(bytes.length);
      ReplicatorMetrics.totalPublishedLocalGoodEventsBytes.addAndGet(bytes.length);
      ReplicatorMetrics.totalPublishedLocalGoodEvents.incrementAndGet();
      ReplicatorMetrics.totalPublishedLocalEventsByType.add(originalEvent.getEventOrigin());
    }

    return writeEventsToFile(originalEvent.getProjectName(), bytes);
  }


  /**
   * Rename the outgoing events-<randomnum>.tmp file to the unique filename that was created upon
   * construction of the PersistedEventInformation instance.
   * See {@link PersistedEventInformation#createThisEventsFilename(EventWrapper)} ()} ()} for how
   * this filename is composed.
   * @return Returns TRUE when it has successfully renamed the temp file to its final named variety(atomically)
   */
  public boolean atomicRenameTmpFilename() {

    //The final event file name is set upon construction if we encounter a project that is not
    //in our outgoingEventInformationMap
    if (Strings.isNullOrEmpty(getFinalEventFileName())) {
      logger.atSevere().log("RE finalEventFileName was not set correctly, losing events!");
      return false;
    }

    File projectTmpEventFile = getEventFile();

    logger.atFinest().log(".tmp event [ %s ] file for projectName [ %s ] will be renamed to [ %s ]",
        getProjectName(), projectTmpEventFile, getFinalEventFileName());

    File newFile = new File(outputDirectory, getFinalEventFileName());

    // Documentation states the following for 'renameTo'
    // * Many aspects of the behavior of this method are inherently
    // * platform-dependent: The rename operation might not be able to move a
    // * file from one filesystem to another, it might not be atomic, and it
    // * might not succeed if a file with the destination abstract pathname
    // * already exists. The return value should always be checked to make sure
    // * that the rename operation was successful.
    // We should therefore consider an alternative in future.
    boolean renamed = projectTmpEventFile.renameTo(newFile);

    if (!renamed) {
      logger.atSevere().log("RE Could not rename file to be picked up, losing events! %s",
          projectTmpEventFile.getAbsolutePath());
      return false;
    }

    ReplicatorMetrics.totalPublishedLocalEventsProsals.incrementAndGet();
    return true;
  }


  /**
   * We record the time that the first event was written to the file, we compare this against the
   * time now. If the difference is greate than or equal to the getMaxSecsToWaitBeforeProposingEvents then
   * return true.
   * @return true if the difference between the current time and the time which the first event was written
   * is >= the max seconds to wait before proposing events value.
   */
  public boolean timeToWaitBeforeProposingExpired(){
    long timeNow = System.currentTimeMillis();
    return (timeNow - getFirstEventTime() ) >= replicatedConfiguration.getMaxSecsToWaitBeforeProposingEvents();
  }

  /**
   * If the number of written events to the event file is greater than or equal
   * to the maxNumberOfEventsBeforeProposing then return true. If we have
   * reached the maxNumberOfEventsBeforeProposing then we must propose,
   * otherwise we can just continue to add events to the file. The default value
   * for maxNumberOfEventsBeforeProposing is 30 although this is configurable by
   * setting gerrit.replicated.events.max.append.before.proposing in the
   * application.properties.
   *
   * @return true if the number of events written to the file so far is >= the max number of events
   * allowed before proposing.
   */
  public boolean exceedsMaxEventsBeforeProposing() {
    return getNumEventsWritten().get() >= replicatedConfiguration.getMaxNumberOfEventsBeforeProposing();
  }


  /**
   * Allow the creation of an events file name to be used by our real code, and integration testing
   * to keep naming consistent.
   * @param originalEvent
   * @return String containing the new Events filename
   */
  public String createThisEventsFilename(final EventWrapper originalEvent) throws IOException {
    // Creating a GerritEventData object from the inner event JSON of the
    // EventWrapper object.
    GerritEventData eventData = ObjectUtils
        .createObjectFromJson(originalEvent.getEvent(), GerritEventData.class);

    if (eventData == null) {
      logger.atSevere().log("Unable to set event filename, could not create "
          + "GerritEventData object from JSON %s", originalEvent.getEvent());
      throw new IOException("Unable to create a new GerritEventData object from the event supplied.");
    }

    // If there are event types added in future that do not support the
    // projectName member
    // then we should generate an error. The default sha1 will be used.
    if (originalEvent.getProjectName() == null) {
      //This message is logged at INFO level due to the replication plugin causing at lot of spam on the dashboard.
      logger.atInfo().log("The following Event Type %s has a Null project name. "
              + "Unable to set the event filename using the sha1 of the project name. "
              + "Using All-Projects as the default project, and updating the event data to match",
          originalEvent.getEvent());
      // lets sort this out.
      originalEvent.setProjectName(replicatedConfiguration.getAllProjectsName());
    }

    String eventTimestamp = eventData.getEventTimestamp();
    // The java.lang.System.nanoTime() method returns the current value of
    // the most precise available system timer, in nanoseconds. The value
    // returned represents elapsed time of the current JVM from some fixed but arbitrary *origin* time
    // (*potentially* in the future, so values may be negative)
    // and provides nanosecond precision, but not necessarily nanosecond
    // accuracy.

    // The long value returned will be represented as a padded hexadecimal value
    // to 16 digits in order to have a
    // guaranteed fixed length as System.nanoTime() varies in length on each OS.
    // If we are dealing with older event files where eventNanoTime doesn't
    // exist as part of the event
    // then we will set the eventNanoTime portion to 16 zeros, same length as a
    // nanoTime represented as HEX.
    String eventNanoTime = eventData.getEventNanoTime() != null
        ? ObjectUtils.getHexStringOfLongObjectHash(
        Long.parseLong(eventData.getEventNanoTime()))
        : DEFAULT_NANO;
    String eventTimeStr = String.format("%sx%s", eventTimestamp, eventNanoTime);
    String objectHash =
        ObjectUtils.getHexStringOfIntObjectHash(originalEvent.hashCode());

    // event file is now following the format
    // events_<eventTimeStamp>x<eventNanoTime>_<nodeId>_<repo-sha1>_<hashOfEventContents>.json

    // The NEXT_EVENTS_FILE variable is formatted with the timestamp and nodeId
    // of the event and
    // a sha1 of the project name. This ensures that it will remain unique under
    // heavy load across projects.
    // Note that a project name includes everything below the root so for
    // example /path/subpath/repo01 is a valid project name.
    return String.format(NEXT_EVENTS_FILE, eventTimeStr,
        eventData.getNodeIdentity(),
        getProjectNameSha1(originalEvent.getProjectName()), objectHash);
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PersistedEventInformation that = (PersistedEventInformation) o;
    return getFirstEventTime() == that.getFirstEventTime() &&
        isFileOutputStreamClosed() == that.isFileOutputStreamClosed() &&
        getEventFile().equals(that.getEventFile()) &&
        getFinalEventFileName().equals(that.getFinalEventFileName()) &&
        getFileOutputStream().equals(that.getFileOutputStream()) &&
        getNumEventsWritten().equals(that.getNumEventsWritten()) &&
        replicatedConfiguration.equals(that.replicatedConfiguration) &&
        getProjectName().equals(that.getProjectName()) &&
        outputDirectory.equals(that.outputDirectory) &&
        gson.equals(that.gson);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getFirstEventTime(), getEventFile(), getFinalEventFileName(), getFileOutputStream(),
        isFileOutputStreamClosed(), getNumEventsWritten(), replicatedConfiguration, getProjectName(), outputDirectory, gson);
  }


  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("PersistedEventInformation{");
    sb.append("firstEventTime=").append(firstEventTime);
    sb.append(", eventFile=").append(eventFile);
    sb.append(", finalEventFileName='").append(finalEventFileName).append('\'');
    sb.append(", isFileOutputStreamClosed=").append(isFileOutputStreamClosed);
    sb.append(", numEventsWritten=").append(numEventsWritten);
    sb.append(", replicatedConfiguration=").append(replicatedConfiguration);
    sb.append(", projectName='").append(projectName).append('\'');
    sb.append(", outputDirectory=").append(outputDirectory).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
