package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metric accumulator and real-time health tracker for the incoming RTSP video stream.
 * <p>
 * Decouples low-level FFmpeg process logging from REST status endpoints and Kubernetes liveness probes.
 * All metrics utilize lock-free atomic primitives to ensure zero-latency reads by HTTP worker threads
 * even while the background watchdog is performing restarts.
 */
@ApplicationScoped
public class StreamHealthTracker {

    private static final Logger LOG = Logger.getLogger(StreamHealthTracker.class);

    private final AtomicLong lastFrameTimestamp = new AtomicLong(0L);
    private final AtomicLong restartCount = new AtomicLong(0L);
    private final AtomicLong totalSegmentsWritten = new AtomicLong(0L);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(false);

    /**
     * Records arrival of a new video/audio frame progress update from FFmpeg.
     * Invoked frequently by {@code FFmpegLogPump}.
     * <p>
     * Confirms that the physical RTSP stream is actively delivering valid frame packets,
     * transitioning {@code alive} to {@code true}.
     */
    public void recordFrameProgress() {
        this.lastFrameTimestamp.set(System.currentTimeMillis());
        this.alive.set(true);
        this.running.set(true);
    }

    /**
     * Records an abnormal disconnection, subprocess crash, or reconnection trigger event.
     * <p>
     * <b>Why {@code alive} is explicitly set to {@code false}:</b><br>
     * This method is invoked immediately upon detecting that the previous FFmpeg process has exited abnormally
     * (e.g. Wi-Fi dropout, camera power loss, RTSP connection reset). During the subsequent backoff delay period
     * and reconnection attempt, the physical stream is dead. Setting {@code alive = false} accurately reflects
     * this offline state to REST telemetry and Kubernetes health probes.
     * <p>
     * <b>Transition back to {@code alive = true}:</b><br>
     * Merely invoking {@code executor.start()} does NOT immediately restore {@code alive = true}, because RTSP TCP
     * handshakes or camera authentication may still fail. The stream is only marked {@code alive = true}
     * when the first valid video frame actually arrives and triggers {@link #recordFrameProgress()}.
     */
    public void recordDisconnection() {
        long currentRestarts = this.restartCount.incrementAndGet();
        this.alive.set(false);
        LOG.warnf("Stream disconnection recorded (cumulative restarts: %d)", currentRestarts);
    }

    /**
     * Alias for {@link #recordDisconnection()}.
     *
     * @deprecated prefer using {@link #recordDisconnection()} for precise semantic clarity.
     */
    @Deprecated
    public void recordRestart() {
        recordDisconnection();
    }

    /**
     * Records the successful finalization of an individual video segment file.
     */
    public void recordSegmentCompleted() {
        long total = this.totalSegmentsWritten.incrementAndGet();
        LOG.debugf("Segment successfully completed (cumulative segments: %d)", total);
    }

    /**
     * Updates the supervisor running state.
     *
     * @param isRunning whether the supervisor loop is active
     */
    public void setRunning(boolean isRunning) {
        this.running.set(isRunning);
        if (!isRunning) {
            this.alive.set(false);
        }
    }

    /**
     * Evaluates whether the stream has stalled (no video frames arrived within the specified timeout).
     * Used by the MicroProfile Liveness health check to trigger pod eviction if the stream freezes.
     *
     * @param timeoutMillis maximum allowable duration in milliseconds without any frame progress
     * @return {@code true} if the stream is running but has not received frames within the timeout window
     */
    public boolean isStreamStalled(long timeoutMillis) {
        if (!this.running.get()) {
            return false;
        }

        long lastTs = this.lastFrameTimestamp.get();
        if (lastTs == 0L) {
            // Stream was just started, allow startup grace period
            return false;
        }

        // Calculate the elapsed time in milliseconds since the last video/audio frame was received.
        // If the elapsed duration exceeds timeoutMillis (default 60 seconds), it signals a "Zombie/Frozen Stream":
        // the FFmpeg OS process may still be running, but TCP RTSP packets have halted without triggering process exit.
        // Returning true causes the Kubernetes Liveness Probe (/q/health/live) to report 503 DOWN,
        // triggering Kubelet to restart the Pod as the ultimate safety net.
        long elapsed = System.currentTimeMillis() - lastTs;
        if (elapsed > timeoutMillis) {
            LOG.warnf("RTSP stream stalled! No frames received for %d ms (threshold: %d ms)", elapsed, timeoutMillis);
            return true;
        }

        return false;
    }

    /**
     * Generates an immutable point-in-time snapshot of the stream's operational metrics.
     *
     * @return {@link StreamStatusSnapshot}
     */
    public StreamStatusSnapshot getStatusSnapshot() {
        long now = System.currentTimeMillis();
        long lastTs = this.lastFrameTimestamp.get();
        long secondsSinceLast = lastTs > 0L ? Math.max(0L, (now - lastTs) / 1000L) : -1L;

        return new StreamStatusSnapshot(
                this.running.get(),
                this.alive.get(),
                lastTs,
                secondsSinceLast,
                this.restartCount.get(),
                this.totalSegmentsWritten.get(),
                Instant.ofEpochMilli(now)
        );
    }

    /**
     * Resets all metric accumulators to their initial zero states.
     */
    public void reset() {
        this.lastFrameTimestamp.set(0L);
        this.restartCount.set(0L);
        this.totalSegmentsWritten.set(0L);
        this.running.set(false);
        this.alive.set(false);
    }
}
