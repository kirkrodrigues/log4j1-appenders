package com.yscope.logging.log4j1;

import java.io.Flushable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Level;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Base class for Log4j file appenders with specific design characteristics;
 * namely, the appenders:
 * <ol>
 *   <li>Buffer logs, e.g. for streaming compression.</li>
 *   <li>Rollover log files based on some policy, e.g. exceeding a threshold
 *   size.</li>
 *   <li>Flush and synchronize log files (e.g. to remote storage) based on how
 *   fresh they are.</li>
 * </ol>
 * For instance, such an appender might compress log events as they are
 * generated, while still flushing and uploading them to remote storage a few
 * seconds after an error log event.
 * <p></p>
 * This class handles keeping track of how fresh the logs are and the high-level
 * logic to trigger flushing, syncing, and rollover at the appropriate times.
 * Derived classes must implement methods to do the actual flushing, syncing,
 * and rollover as well as indicate whether rollover is necessary.
 * <p></p>
 * The freshness property maintained by this class allows users to specify the
 * delay between a log event being generated and the log file being flushed and
 * synchronized. There are two types of delays that can be specified, and each
 * can be specified per log level:
 <ul>
 *   <li><b>hard timeouts</b> - these timeouts cause a hard deadline to be set
 *   for when flushing must occur. E.g., if log event occurs at time t, then a
 *   hard deadline is set at time (t + hardTimeout). This hard deadline may be
 *   decreased if a subsequent log event has an associated hard timeout that
 *   would result in an earlier hard deadline.</li>
 *   <li><b>soft timeouts</b> - these timeouts cause a soft deadline to be set
 *   for when flushing should occur. Unlike the hard deadline, this deadline may
 *   be increased if a subsequent log event occurs before any deadline is
 *   reached. Note however, that the timeout used to calculate the soft deadline
 *   is set to the minimum of the timeouts associated with the log events that
 *   have occurred before the deadline. E.g., if a log event associated with a
 *   5s soft timeout occurs, and then is followed by a log event associated with
 *   a 10s soft timeout, the soft deadline will be set as if the second log
 *   event had a had 5s soft timeout.</li>
 * </ul>
 * Once a deadline is reached, the current timeouts and deadlines are reset.
 * <p></p>
 * For example, let's assume the soft and hard timeouts for ERROR logs are set
 * to 5 seconds and 5 minutes respectively. Now imagine an ERROR log event is
 * generated at t = 0s. This class will trigger a flush at t = 5s unless another
 * ERROR log event is generated before then. If one is generated at t = 4s, then
 * this class will omit the flush at t = 5s and trigger a flush at t = 9s. If
 * ERROR log events keep being generated before a flush occurs, then this class
 * will definitely trigger a flush at t = 5min based on the hard timeout.
 * <p></p>
 * Maintaining these timeouts per log level allows us to flush logs sooner if
 * more important log levels occur. For instance, we can set smaller timeouts
 * for ERROR log events compared to DEBUG log events.
 */
public abstract class AbstractBufferedRollingFileAppender extends EnhancedAppenderSkeleton
    implements Flushable
{
  protected final TimeSource timeSource;
  protected long lastRolloverTimestamp;

  // Appender settings, some of which may be set by Log4j through reflection.
  // For descriptions of the properties, see their setters below.
  private String baseName = null;
  private boolean closeFileOnShutdown = true;
  private final HashMap<Level, Long> flushHardTimeoutPerLevel = new HashMap<>();
  private final HashMap<Level, Long> flushSoftTimeoutPerLevel = new HashMap<>();
  private int timeoutCheckPeriod = 1000;

  private long flushHardTimeoutTimestamp;
  private long flushSoftTimeoutTimestamp;
  // The maximum soft timeout allowed. If users wish to continue log collection
  // and synchronization after appender is interrupted, this value will be
  // artificiality lowered to increase the likelihood of flushing before the
  // app terminates.
  private long flushMaximumSoftTimeout;

  private final BackgroundFlushThread backgroundFlushThread = new BackgroundFlushThread();
  private final BackgroundSyncThread backgroundSyncThread = new BackgroundSyncThread();

  private long numEventsLogged = 0L;

  private boolean activated = false;

  /**
   * Default constructor
   */
  public AbstractBufferedRollingFileAppender () {
    this(new SystemTimeSource());
  }

  /**
   * Constructor to enable the ability to specify a TimeSource, such as those
   * that can be controlled manually to facilitate unit testing
   * @param timeSource The time source that the appender should use
   */
  public AbstractBufferedRollingFileAppender (TimeSource timeSource) {
    this.timeSource = timeSource;

    // The default flush timeout values below are optimized for high latency
    // remote persistent storage such as object stores or HDFS
    flushHardTimeoutPerLevel.put(Level.FATAL, 5L * 60 * 1000 /* 5 min */);
    flushHardTimeoutPerLevel.put(Level.ERROR, 5L * 60 * 1000 /* 5 min */);
    flushHardTimeoutPerLevel.put(Level.WARN, 10L * 60 * 1000 /* 10 min */);
    flushHardTimeoutPerLevel.put(Level.INFO, 30L * 60 * 1000 /* 30 min */);
    flushHardTimeoutPerLevel.put(Level.DEBUG, 30L * 60 * 1000 /* 30 min */);
    flushHardTimeoutPerLevel.put(Level.TRACE, 30L * 60 * 1000 /* 30 min */);

    flushSoftTimeoutPerLevel.put(Level.FATAL, 5L * 1000 /* 5 sec */);
    flushSoftTimeoutPerLevel.put(Level.ERROR, 10L * 1000 /* 10 sec */);
    flushSoftTimeoutPerLevel.put(Level.WARN, 15L * 1000 /* 15 sec */);
    flushSoftTimeoutPerLevel.put(Level.INFO, 3L * 60 * 1000 /* 3 min */);
    flushSoftTimeoutPerLevel.put(Level.DEBUG, 3L * 60 * 1000 /* 3 min */);
    flushSoftTimeoutPerLevel.put(Level.TRACE, 3L * 60 * 1000 /* 3 min */);
  }

  /**
   * @param baseName The base filename for log files
   */
  public void setBaseName (String baseName) {
    this.baseName = baseName;
  }

  /**
   * Sets whether to close the log file upon receiving a shutdown signal before
   * the JVM exits. If set to false, the appender will continue appending logs
   * even while the JVM is shutting down and the appender will do its best to
   * sync those logs before the JVM shuts down. This presents a tradeoff
   * between capturing more log events and potential data loss if the log events
   * cannot be flushed and synced before the JVM shuts down.
   * @param closeFileOnShutdown Whether to close the log file on shutdown
   */
  public void setCloseFileOnShutdown (boolean closeFileOnShutdown) {
    this.closeFileOnShutdown = closeFileOnShutdown;
  }

  /**
   * Sets the per-log-level hard timeouts for flushing.
   * @param csvTimeouts A CSV string of kv-pairs. The key being the log-level in
   * all caps and the value being the hard timeout for flushing in minutes. E.g.
   * "INFO=30,WARN=10,ERROR=5"
   */
  public void setFlushHardTimeoutsInMinutes (String csvTimeouts) {
    for (String token : csvTimeouts.split(",")) {
      String[] kv = token.split("=");
      if (isSupportedLogLevel(kv[0])) {
        try {
          flushHardTimeoutPerLevel.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 60 * 1000);
        } catch (UnsupportedOperationException ex) {
          logError("Failed to set hard flush timeout value " +
              "for the " + kv[0] + " verbosity level: " + kv[1]);
        }
      } else {
        logError("Failed to set hard flush timeout " +
            "for the following unsupported verbosity level: " + kv[0]);
      }
    }
  }

  /**
   * Sets the per-log-level soft timeouts for flushing.
   * @param csvTimeouts A CSV string of kv-pairs. The key being the log-level in
   * all caps and the value being the soft timeout for flushing in seconds. E.g.
   * "INFO=180,WARN=15,ERROR=10"
   */
  public void setFlushSoftTimeoutsInSeconds (String csvTimeouts) {
    for (String token : csvTimeouts.split(",")) {
      String[] kv = token.split("=");
      if (isSupportedLogLevel(kv[0])) {
        try {
          flushSoftTimeoutPerLevel.put(Level.toLevel(kv[0]), Long.parseLong(kv[1]) * 1000);
        } catch (UnsupportedOperationException ex) {
          logError("Failed to set soft flush timeout value " +
              "for the " + kv[0] + " verbosity level: " + kv[1]);
        }
      } else {
        logError("Failed to set soft flush timeout " +
            "for the following unsupported verbosity level: " + kv[0]);
      }
    }
  }

  /**
   * Sets the period between checking for soft/hard timeouts (and then
   * triggering a flush and sync). Care should be taken to ensure this period
   * does not significantly differ from the lowest timeout since that will
   * cause undue delay from when a timeout expires and when a flush occurs.
   * @param milliseconds The period in milliseconds
   */
  public void setTimeoutCheckPeriod (int milliseconds) {
    timeoutCheckPeriod = milliseconds;
  }

  public String getBaseName () {
    return baseName;
  }

  public long getNumEventsLogged () {
    return numEventsLogged;
  }

  /**
   * Activates the appender's options.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #activateOptionsHook(long)} method.
   */
  @Override
  public final void activateOptions () {
    if (closed) {
      logWarn("Already closed so cannot activate options.");
      return;
    }

    if (activated) {
      logWarn("Already activated.");
      return;
    }

    resetFreshnessTimeouts();

    try {
      // Set the first rollover timestamp to the current time
      lastRolloverTimestamp = System.currentTimeMillis();
      activateOptionsHook(lastRolloverTimestamp);
      if (closeFileOnShutdown) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
      }
      backgroundFlushThread.start();
      backgroundSyncThread.start();

      activated = true;
    } catch (Exception ex) {
      logError("Failed to activate appender.", ex);
      closed = true;
    }
  }

  /**
   * Closes the appender.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #closeHook()} method.
   */
  @Override
  public final synchronized void close () {
    if (closed) {
      return;
    }

    if (closeFileOnShutdown) {
      try {
        closeHook();
      } catch (Exception ex) {
        // Just log the failure but continue the close process
        logError("closeHook failed.", ex);
      }
      backgroundSyncThread.addSyncRequest(baseName, lastRolloverTimestamp, true,
                                          computeSyncRequestMetadata());
      backgroundSyncThread.addShutdownRequest();
    } else {
      try {
        // Flush now just in case we shut down before a timeout expires (and
        // triggers a flush)
        flush();
      } catch (IOException e) {
        logError("Failed to flush", e);
      }
      backgroundSyncThread.addSyncRequest(baseName, lastRolloverTimestamp, false,
                                          computeSyncRequestMetadata());
    }

    closed = true;
  }

  /**
   * Appends the given log event to the file (subject to any buffering by the
   * derived class). This method may also trigger a rollover and sync if the
   * derived class' {@link #rolloverRequired()} method returns true.
   * <p></p>
   * This method is {@code final} to ensure it is not overridden by derived
   * classes since this base class needs to perform actions before/after the
   * derived class' {@link #appendHook(LoggingEvent)} method. This method is
   * also marked {@code synchronized} since it can be called from multiple
   * logging threads.
   * @param loggingEvent The log event
   */
  @Override
  public final synchronized void append (LoggingEvent loggingEvent) {
    try {
      appendHook(loggingEvent);
      ++numEventsLogged;

      if (false == rolloverRequired()) {
        updateFreshnessTimeouts(loggingEvent);
      } else {
        backgroundSyncThread.addSyncRequest(baseName, lastRolloverTimestamp, true,
                                            computeSyncRequestMetadata());
        resetFreshnessTimeouts();
        lastRolloverTimestamp = loggingEvent.getTimeStamp();
        startNewLogFile(lastRolloverTimestamp);
        numEventsLogged = 0L;
      }
    } catch (Exception ex) {
      getErrorHandler().error("Failed to write log event.", ex, ErrorCode.WRITE_FAILURE);
    }
  }

  /**
   * @return Whether the appender requires a layout
   */
  @Override
  public boolean requiresLayout () {
    return true;
  }

  /**
   * Activates appender options for derived appenders.
   * @param currentTimestamp Current timestamp (useful for naming the first log
   * file)
   */
  protected abstract void activateOptionsHook (long currentTimestamp) throws Exception;

  /**
   * Closes the derived appender. Once closed, the appender cannot be reopened.
   */
  protected abstract void closeHook () throws Exception;

  /**
   * @return Whether to trigger a rollover
   */
  protected abstract boolean rolloverRequired () throws Exception;

  /**
   * Starts a new log file.
   * @param lastEventTimestamp Timestamp of the last event that was logged
   * before calling this method (useful for naming the new log file).
   */
  protected abstract void startNewLogFile (long lastEventTimestamp) throws Exception;

  /**
   * Synchronizes a log file with remote storage. Note that this file may not
   * necessarily be the current log file, but a previously rolled-over one.
   * @param baseName The base filename of the log file to sync
   * @param logRolloverTimestamp The approximate timestamp of when the target
   * log file was rolled over
   * @param deleteFile Whether the log file can be deleted after syncing
   * @param fileMetadata Extra metadata for the file that was captured at the
   * time when the sync request was generated
   */
  protected abstract void sync (String baseName, long logRolloverTimestamp, boolean deleteFile,
                                Map<String, Object> fileMetadata) throws Exception;

  /**
   * Appends a log event to the file.
   * @param event The log event
   */
  protected abstract void appendHook (LoggingEvent event) throws Exception;

  /**
   * Computes the log file name, which includes the provided base name and
   * rollover timestamp.
   * @param baseName The base name of the log file name
   * @param logRolloverTimestamp The approximate timestamp when the target log
   * file was rolled over
   * @return The computed log file name
   */
  protected abstract String computeLogFileName (String baseName, long logRolloverTimestamp);

  /**
   * Computes the file metadata to be included in a synchronization request
   * @return The computed file metadata
   */
  protected Map<String, Object> computeSyncRequestMetadata () {
    return null;
  }

  /**
   * Tests if log level is supported by this appender configuration
   * @param level string passed in from configuration parameter
   * @return true if supported, false otherwise
   */
  public boolean isSupportedLogLevel(String level) {
    // Note that the Level class is able to automatically parses a candidate
    // level string into a log4j level, and if it cannot, it will assign the
    // log4j level as to the default "INFO" level.
    return Level.toLevel(level) != Level.INFO || level.equals("INFO");
  }

  /**
   * Resets the soft/hard freshness timeouts.
   */
  private void resetFreshnessTimeouts () {
    flushHardTimeoutTimestamp = Long.MAX_VALUE;
    flushSoftTimeoutTimestamp = Long.MAX_VALUE;
    if (Thread.currentThread().isInterrupted()) {
      // Since the thread has been interrupted (presumably because the app is
      // being shut down), lower the maximum soft timeout to increase the
      // likelihood that the log will be synced before the app shuts down.
      flushMaximumSoftTimeout = flushSoftTimeoutPerLevel.get(Level.FATAL);
    } else {
      flushMaximumSoftTimeout = flushSoftTimeoutPerLevel.get(Level.TRACE);
    }
  }

  /**
   * Updates the soft/hard freshness timeouts based on the given log event's log
   * level and timestamp.
   * @param loggingEvent The log event
   */
  private void updateFreshnessTimeouts (LoggingEvent loggingEvent) {
    Level level = loggingEvent.getLevel();
    long timeoutTimestamp = loggingEvent.timeStamp + flushHardTimeoutPerLevel.get(level);
    flushHardTimeoutTimestamp = Math.min(flushHardTimeoutTimestamp, timeoutTimestamp);

    flushMaximumSoftTimeout = Math.min(flushMaximumSoftTimeout,
                                       flushSoftTimeoutPerLevel.get(level));
    flushSoftTimeoutTimestamp = loggingEvent.timeStamp + flushMaximumSoftTimeout;
  }

  /**
   * Flushes and synchronizes the log file if one of the freshness timeouts has
   * been reached.
   * <p></p>
   * This method is marked {@code synchronized} since it can be called from
   * logging threads and the background thread that monitors the freshness
   * timeouts.
   * @throws IOException on I/O error
   */
  private synchronized void flushAndSyncIfNecessary () throws IOException {
    long ts = timeSource.getCurrentTimeInMilliseconds();
    if (ts >= flushSoftTimeoutTimestamp || ts >= flushHardTimeoutTimestamp) {
      flush();
      backgroundSyncThread.addSyncRequest(baseName, lastRolloverTimestamp, false,
                                          computeSyncRequestMetadata());
      resetFreshnessTimeouts();
    }
  }

  /**
   * Periodically flushes and syncs the current log file if we've exceeded one
   * of the freshness timeouts.
   */
  private class BackgroundFlushThread extends Thread {
    @Override
    public void run () {
      while (true) {
        try {
          flushAndSyncIfNecessary();
          sleep(timeoutCheckPeriod);
        } catch (IOException e) {
          logError("Failed to flush buffered appender in the background", e);
        } catch (InterruptedException e) {
          if (closeFileOnShutdown) {
            logDebug("Received interrupt message for graceful shutdown of BackgroundFlushThread");
            break;
          }
        }
      }
    }
  }

  /**
   * Thread to synchronize log files in the background (by calling
   * {@link #sync(String, long, boolean, Map<String, Object>) sync}). The thread
   * maintains a request queue that callers should populate.
   */
  private class BackgroundSyncThread extends Thread {
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    @Override
    public void run () {
      while (true) {
        try {
          Request request = requests.take();
          if (request instanceof SyncRequest) {
            SyncRequest syncRequest = (SyncRequest)request;
            try {
              sync(syncRequest.logFileBaseName, syncRequest.logRolloverTimestamp,
                   syncRequest.deleteFile, syncRequest.fileMetadata);
            } catch (Exception ex) {
              String logFilePath = computeLogFileName(syncRequest.logFileBaseName,
                                                      syncRequest.logRolloverTimestamp);
              logError("Failed to sync '" + logFilePath + "'", ex);
            }
          } else if (request instanceof ShutdownRequest) {
            logDebug("Received shutdown request");
            break;
          }
        } catch (InterruptedException e) {
          // Ignore the exception since we want to continue syncing logs even
          // in case of exceptions (the thread only shuts down when a shutdown
          // request is made)
        }
      }
    }

    /**
     * Adds a shutdown request to the request queue
     */
    public void addShutdownRequest () {
      logDebug("Adding shutdown request");
      Request shutdownRequest = new ShutdownRequest();
      while (false == requests.offer(shutdownRequest)) {}
    }

    /**
     * Adds a sync request to the request queue
     * @param baseName The base filename of the log file to sync
     * @param logRolloverTimestamp The approximate timestamp of when the target
     * log file was rolled over
     * @param deleteFile Whether the log file can be deleted after syncing.
     * @param fileMetadata Extra metadata for the file
     */
    public void addSyncRequest (String baseName, long logRolloverTimestamp, boolean deleteFile,
                                Map<String, Object> fileMetadata) {
      Request syncRequest =
          new SyncRequest(baseName, logRolloverTimestamp, deleteFile, fileMetadata);
      while (false == requests.offer(syncRequest)) {}
    }

    private class Request {}

    private class ShutdownRequest extends Request {}

    private class SyncRequest extends Request {
      public final String logFileBaseName;
      public final long logRolloverTimestamp;
      public final boolean deleteFile;
      public final Map<String, Object> fileMetadata;

      public SyncRequest (String logFileBaseName, long logRolloverTimestamp, boolean deleteFile,
                          Map<String, Object> fileMetadata) {
        this.logFileBaseName = logFileBaseName;
        this.logRolloverTimestamp = logRolloverTimestamp;
        this.deleteFile = deleteFile;
        this.fileMetadata = fileMetadata;
      }
    }
  }
}
