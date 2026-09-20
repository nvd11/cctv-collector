package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.model.UploadStatusSnapshot;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metric accumulator tracking cloud upload throughput and job statuses.
 */
@ApplicationScoped
public class UploadHealthTracker {

    private final AtomicLong totalUploadedFiles = new AtomicLong(0L);
    private final AtomicLong totalUploadedBytes = new AtomicLong(0L);
    private final AtomicLong failedUploads = new AtomicLong(0L);
    private final AtomicLong lastUploadTimestamp = new AtomicLong(0L);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void recordUploadSuccess(long bytes) {
        this.totalUploadedFiles.incrementAndGet();
        this.totalUploadedBytes.addAndGet(Math.max(0L, bytes));
        this.lastUploadTimestamp.set(System.currentTimeMillis());
    }

    public void recordUploadFailure() {
        this.failedUploads.incrementAndGet();
    }

    public void setRunning(boolean isRunning) {
        this.running.set(isRunning);
    }

    public boolean isRunning() {
        return this.running.get();
    }

    public UploadStatusSnapshot getStatusSnapshot(int activeQueueSize) {
        long now = System.currentTimeMillis();
        long lastTs = this.lastUploadTimestamp.get();
        long secondsSinceLast = (lastTs > 0L) ? Math.max(0L, (now - lastTs) / 1000L) : -1L;

        return new UploadStatusSnapshot(
                this.running.get(),
                this.totalUploadedFiles.get(),
                this.totalUploadedBytes.get(),
                this.failedUploads.get(),
                lastTs,
                secondsSinceLast,
                activeQueueSize,
                Instant.ofEpochMilli(now)
        );
    }

    public void reset() {
        this.totalUploadedFiles.set(0L);
        this.totalUploadedBytes.set(0L);
        this.failedUploads.set(0L);
        this.lastUploadTimestamp.set(0L);
        this.running.set(false);
    }
}
