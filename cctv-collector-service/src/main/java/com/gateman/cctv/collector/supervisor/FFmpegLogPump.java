package com.gateman.cctv.collector.supervisor;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Continuous pipe reader and telemetry pump for native FFmpeg process standard output/error streams.
 * <p>
 * <b>Why this component is vital:</b><br>
 * Linux operating system pipes have a strict 64KB kernel buffer limit. If a spawned process produces output
 * on {@code stderr} or {@code stdout} that is not continuously drained by the parent Java process,
 * the kernel pipe buffer will fill up within seconds, causing the operating system to permanently block (freeze)
 * the FFmpeg subprocess.
 * <p>
 * This pump runs on a dedicated worker thread (or Java 21 Virtual Thread), draining the pipe non-stop,
 * parsing frame progress telemetry (e.g. {@code frame=... fps=... time=...}), and feeding real-time heartbeats
 * directly into {@link StreamHealthTracker#recordFrameProgress()}.
 */
public class FFmpegLogPump implements Runnable, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FFmpegLogPump.class);

    private final InputStream inputStream;
    private final StreamHealthTracker healthTracker;
    private final Consumer<String> lineConsumer;
    private final AtomicLong lastActivityTimestamp = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructs a log pump with a stream health tracker and optional custom line consumer.
     *
     * @param inputStream   the process stream to drain (typically process.getErrorStream())
     * @param healthTracker the tracker to update upon receiving video frames
     * @param lineConsumer  optional consumer for custom line handling or testing (may be null)
     */
    public FFmpegLogPump(InputStream inputStream, StreamHealthTracker healthTracker, Consumer<String> lineConsumer) {
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
        this.healthTracker = healthTracker;
        this.lineConsumer = lineConsumer;
    }

    /**
     * Constructs a standard log pump bound to the stream health tracker.
     *
     * @param inputStream   the process stream to drain
     * @param healthTracker the tracker to update
     */
    public FFmpegLogPump(InputStream inputStream, StreamHealthTracker healthTracker) {
        this(inputStream, healthTracker, null);
    }

    @Override
    public void run() {
        LOG.debug("FFmpegLogPump started draining process stream");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                processLine(line);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.debugf("FFmpegLogPump stream closed or finished: %s", e.getMessage());
            }
        } finally {
            close();
            LOG.debug("FFmpegLogPump worker thread terminated");
        }
    }

    /**
     * Parses an individual line emitted by FFmpeg, updates activity timestamps,
     * feeds health trackers, and dispatches to consumers.
     *
     * @param line raw text line from process output
     */
    protected void processLine(String line) {
        if (line == null) {
            return;
        }

        long now = System.currentTimeMillis();
        this.lastActivityTimestamp.set(now);

        // Dispatches to custom consumer if attached
        if (lineConsumer != null) {
            try {
                lineConsumer.accept(line);
            } catch (Exception e) {
                LOG.warnf("Line consumer encountered an error: %s", e.getMessage());
            }
        }

        // Detect frame progress (e.g. "frame=  120 fps= 15 ... time=00:00:08.00")
        if (isFrameProgressLine(line)) {
            if (healthTracker != null) {
                healthTracker.recordFrameProgress();
            }
            LOG.tracef("FFmpeg progress: %s", line);
            return;
        }

        // Detect segment switching events (e.g. "[segment @ 0x...] Opening '...' for writing")
        if (line.contains("[segment") && line.contains("Opening") && line.contains("for writing")) {
            LOG.infof("FFmpeg segment switch detected: %s", line);
            return;
        }

        // Log warnings and errors with appropriate visibility
        if (line.contains("error") || line.contains("Error") || line.contains("fatal") || line.contains("Connection refused")) {
            LOG.warnf("FFmpeg diagnostic warning: %s", line);
        } else {
            LOG.debugf("FFmpeg: %s", line);
        }
    }

    /**
     * Determines whether a log line indicates real-time frame delivery progress.
     */
    public static boolean isFrameProgressLine(String line) {
        if (line == null) {
            return false;
        }
        // FFmpeg standard progress indicators
        return (line.contains("frame=") || line.contains("fps=")) && line.contains("time=");
    }

    /**
     * Returns the timestamp (in epoch milliseconds) when output was last read from the stream.
     *
     * @return epoch milliseconds
     */
    public long getLastActivityTime() {
        return this.lastActivityTimestamp.get();
    }

    /**
     * Checks whether the pump has been closed.
     *
     * @return {@code true} if closed, {@code false} otherwise
     */
    public boolean isClosed() {
        return this.closed.get();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            try {
                this.inputStream.close();
            } catch (IOException ignored) {
                // Ignore closing exceptions
            }
        }
    }
}
