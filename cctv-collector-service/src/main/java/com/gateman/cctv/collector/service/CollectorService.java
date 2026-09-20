package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.model.VideoStreamProfile;

import java.util.List;
import java.util.Optional;

/**
 * Enterprise domain service facade coordinating stream ingestion, watchdog supervision,
 * disk capacity auditing, and segment metadata queries.
 * <p>
 * Decouples external REST API resources and health check probes from internal supervisor,
 * tracker, and DAO implementation details.
 */
public interface CollectorService {

    /**
     * Starts the 24x7 stream collection and watchdog supervisor.
     */
    void startCollection();

    /**
     * Stops stream collection and cleanly finalizes the active segment.
     */
    void stopCollection();

    /**
     * Triggers an immediate restart of the FFmpeg recording process.
     */
    void restartCollection();

    /**
     * Assembles a real-time point-in-time status snapshot of stream health and performance metrics.
     *
     * @return current {@link StreamStatusSnapshot}
     */
    StreamStatusSnapshot getStatus();

    /**
     * Retrieves the camera technical profile and connection specifications.
     *
     * @return {@link VideoStreamProfile}
     */
    VideoStreamProfile getProfile();

    /**
     * Queries recently finalized video segments, ordered newest first.
     *
     * @param limit maximum number of segments to return
     * @return list of {@link VideoSegment} records
     */
    List<VideoSegment> getRecentSegments(int limit);

    /**
     * Queries the latest active or recently finalized video segment.
     *
     * @return {@link Optional} containing the latest segment
     */
    Optional<VideoSegment> getLatestSegment();

    /**
     * Comprehensive health evaluation for Kubernetes Liveness probes.
     *
     * @return {@code true} if watchdog is running and stream is not frozen
     */
    boolean isHealthy();

    /**
     * Readiness evaluation for Kubernetes Readiness probes.
     *
     * @return {@code true} if storage volume is healthy and supervisor is active
     */
    boolean isReady();
}
