package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.supervisor.DiskHealthChecker;
import com.gateman.cctv.collector.supervisor.FFmpegCommandBuilder;
import com.gateman.cctv.collector.supervisor.FFmpegLogPump;
import com.gateman.cctv.collector.supervisor.FFmpegProcessExecutor;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enterprise supervisor and 24x7 watchdog service for the CCTV stream collector.
 * <p>
 * Serves as the central state machine orchestrating:
 * <ul>
 *   <li>Pre-flight storage circuit breaking (via {@link DiskHealthChecker});</li>
 *   <li>Native process lifecycle and pipe draining (via {@link FFmpegProcessExecutor} and {@link FFmpegLogPump});</li>
 *   <li>Automatic container restart re-hydration (via {@link SegmentLifecycleWatcher#scanBufferDirectory()});</li>
 *   <li>Differentiated exit code self-healing (0ms seamless restart on exitCode 0 vs exponential backoff on exitCode != 0);</li>
 *   <li>Graceful shutdown on Pod termination without damaging the final MP4 moov atom header.</li>
 * </ul>
 */
@ApplicationScoped
public class FFmpegProcessSupervisor {

    private static final Logger LOG = Logger.getLogger(FFmpegProcessSupervisor.class);

    private final CollectorConfig config;
    private final DiskHealthChecker diskChecker;
    private final StreamHealthTracker healthTracker;
    private final FFmpegProcessExecutor executor;
    private final SegmentLifecycleWatcher lifecycleWatcher;

    private final AtomicBoolean shouldRun = new AtomicBoolean(false);
    private final AtomicBoolean loopActive = new AtomicBoolean(false);
    private volatile Thread watchdogThread;
    private volatile FFmpegLogPump logPump;

    /**
     * CDI constructor injection point.
     *
     * @param config           collector configuration
     * @param diskChecker      disk space circuit breaker
     * @param healthTracker    stream health and metrics tracker
     * @param executor         dedicated process executor instance
     * @param lifecycleWatcher physical segment lifecycle auditor
     */
    @Inject
    public FFmpegProcessSupervisor(
            CollectorConfig config,
            DiskHealthChecker diskChecker,
            StreamHealthTracker healthTracker,
            FFmpegProcessExecutor executor,
            SegmentLifecycleWatcher lifecycleWatcher
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.diskChecker = Objects.requireNonNull(diskChecker, "diskChecker must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.lifecycleWatcher = Objects.requireNonNull(lifecycleWatcher, "lifecycleWatcher must not be null");
    }

    /**
     * Quarkus container startup observer.
     */
    void onStartup(@Observes StartupEvent ev) {
        LOG.info("Quarkus startup event received. Re-hydrating historical segments and launching watchdog...");
        try {
            lifecycleWatcher.scanBufferDirectory();
        } catch (Exception e) {
            LOG.warnf("Initial buffer directory scan failed: %s", e.getMessage());
        }
        startSupervisor();
    }

    /**
     * Quarkus container shutdown observer.
     */
    void onShutdown(@Observes ShutdownEvent ev) {
        LOG.info("Quarkus shutdown event received. Triggering graceful shutdown...");
        stopSupervisor();
    }

    /**
     * Starts the background supervisor loop on a dedicated Java 21 Virtual Thread.
     */
    public synchronized void startSupervisor() {
        if (shouldRun.compareAndSet(false, true)) {
            healthTracker.setRunning(true);
            this.watchdogThread = Thread.ofVirtual()
                    .name("cctv-watchdog")
                    .start(this::supervisorLoop);
            Thread.ofVirtual()
                    .name("cctv-stall-watchdog")
                    .start(this::stallWatchLoop);
            LOG.info("FFmpegProcessSupervisor watchdog thread started.");
        }
    }

    /**
     * Stops the background supervisor loop and gracefully finalizes any active recording.
     */
    public synchronized void stopSupervisor() {
        if (shouldRun.compareAndSet(true, false)) {
            healthTracker.setRunning(false);

            // Trigger graceful termination on active process
            executor.stopGracefully(5L);

            if (logPump != null) {
                logPump.close();
            }

            if (watchdogThread != null && watchdogThread.isAlive()) {
                watchdogThread.interrupt();
            }
            LOG.info("FFmpegProcessSupervisor stopped successfully.");
        }
    }

    /**
     * Re-spawns the FFmpeg stream collection process immediately.
     */
    public synchronized void restartSupervisor() {
        LOG.info("Manual restart requested. Gracefully stopping active process and interrupting backoff sleep...");
        executor.stopGracefully(3L);

        if (watchdogThread != null && watchdogThread.isAlive()) {
            watchdogThread.interrupt();
        }
    }

    /**
     * Core 24x7 watchdog event loop.
     */
    public void supervisorLoop() {
        int backoffAttempt = 0;
        loopActive.set(true);

        try {
            while (shouldRun.get()) {
                // 1. Pre-flight storage health & circuit breaker
                if (!diskChecker.isDiskHealthy()) {
                    LOG.error("Disk free space below minimum threshold! Circuit breaker active. Pausing for 30s...");
                    sleepWithInterruption(30_000L);
                    continue;
                }

                // 2. Build production FFmpeg arguments
                List<String> commandArgs = FFmpegCommandBuilder.buildArgs(config);

                // 3. Spawn external FFmpeg process via executor
                try {
                    executor.start(commandArgs);
                } catch (IOException e) {
                    LOG.errorf(e, "Failed to spawn FFmpeg process: %s", e.getMessage());
                    handleAbnormalExit(++backoffAttempt, -1);
                    continue;
                }

                // 4. Start log and progress pump on virtual thread
                InputStream stderr = executor.getErrorStream();
                if (stderr != null) {
                    this.logPump = new FFmpegLogPump(stderr, healthTracker);
                    Thread.ofVirtual().name("cctv-log-pump").start(logPump);
                }

                // 5. Block on OS process termination (Linux waitpid, 0% CPU consumption)
                int exitCode;
                try {
                    exitCode = executor.waitFor();
                } catch (InterruptedException e) {
                    if (shouldRun.get()) {
                        // Manual /api/restart (or an internal respawn request): the interrupt means
                        // "kill the child and spawn a new one", NOT "stop supervising". The old code broke
                        // out of the loop here, which permanently disabled the watchdog after any manual
                        // restart (observed 2026-11-22: "Supervisor watchdog loop finished." right after
                        // POST /api/restart, stream then ran unsupervised forever).
                        Thread.interrupted();
                        backoffAttempt = 0;
                        LOG.info("Respawning FFmpeg after restart request; supervisor loop stays alive.");
                        continue;
                    }
                    Thread.currentThread().interrupt();
                    LOG.info("Watchdog thread interrupted during waitFor(). Exiting loop.");
                    break;
                } finally {
                    if (logPump != null) {
                        logPump.close();
                    }
                }

                // 6. Immediate check for container shutdown signal
                if (!shouldRun.get()) {
                    LOG.info("Shutdown requested. Breaking watchdog loop.");
                    break;
                }

                // 7. Differentiated exit code state machine
                if (exitCode == 0) {
                    LOG.info("FFmpeg process exited normally (exitCode = 0). Resetting backoff and restarting immediately (0ms delay).");
                    backoffAttempt = 0;
                } else {
                    handleAbnormalExit(++backoffAttempt, exitCode);
                }
            }
        } finally {
            loopActive.set(false);
            // Keep the flag consistent with reality. Old code left shouldRun == true after the loop exited,
            // so isRunning() lied and /q/health/live happily reported UP on a collector that had stopped
            // recording (observed 2026-11-22: 198s of zero frames, health UP, liveness never fired).
            shouldRun.set(false);
            healthTracker.setRunning(false);
            LOG.info("Supervisor watchdog loop finished.");
        }
    }

    /**
     * In-process stall watchdog (primary recovery path).
     * <p>
     * Detects the "zombie stream" state where the FFmpeg OS process is still alive but no frames have
     * arrived for {@code cctv.stall-restart-seconds} (camera Wi-Fi dropout, AP de-auth, silently reclaimed
     * RTSP session). It restarts <b>only the FFmpeg child process</b> — the container keeps running, so
     * recovery takes seconds instead of triggering K8s CrashLoopBackOff.
     * <p>
     * The liveness probe ({@code cctv.stall-liveness-seconds}) remains a deliberately slower backstop in
     * case this watchdog itself cannot recover the stream.
     */
    private void stallWatchLoop() {
        final long intervalMillis = 10_000L;
        final long stallMillis = config.stallRestartSeconds() * 1000L;
        LOG.infof("Stall watchdog started (restart after %ds without frames; liveness backstop at %ds).",
                config.stallRestartSeconds(), config.stallLivenessSeconds());

        while (shouldRun.get()) {
            sleepWithInterruption(intervalMillis);
            if (!shouldRun.get()) {
                break;
            }
            if (healthTracker.isStreamStalled(stallMillis)) {
                LOG.warnf("Stall watchdog: no frame progress for over %ds — restarting the FFmpeg child only "
                        + "(container stays alive).", config.stallRestartSeconds());
                // Terminating the child makes waitFor() return, so the supervisor loop respawns it
                // through the normal (backoff-capped) path.
                executor.stopGracefully(3L);
            }
        }
        LOG.info("Stall watchdog stopped.");
    }

    /**
     * Computes exponential backoff delay in seconds.
     *
     * @param attempt reconnection attempt number (1-based)
     * @return delay in seconds
     */
    public int calculateBackoff(int attempt) {
        int initialDelay = config.reconnectDelaySeconds();
        int maxDelay = config.maxReconnectDelaySeconds();

        int exponent = Math.min(Math.max(0, attempt - 1), 6);
        long computedDelay = (long) initialDelay * (1L << exponent);

        return (int) Math.min(computedDelay, (long) maxDelay);
    }

    public boolean isRunning() {
        return shouldRun.get();
    }

    public boolean isLoopActive() {
        return loopActive.get();
    }

    private void handleAbnormalExit(int attempt, int exitCode) {
        healthTracker.recordDisconnection();
        int delaySeconds = calculateBackoff(attempt);

        LOG.warnf("FFmpeg process exited abnormally (exitCode: %d). Triggering backoff attempt #%d (waiting %ds)...",
                exitCode, attempt, delaySeconds);

        sleepWithInterruption(delaySeconds * 1000L);
    }

    private void sleepWithInterruption(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Watchdog sleep interrupted");
        }
    }
}
