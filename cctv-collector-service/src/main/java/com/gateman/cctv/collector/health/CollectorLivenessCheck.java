package com.gateman.cctv.collector.health;

import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import com.gateman.cctv.collector.service.CollectorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

import java.util.Objects;

/**
 * MicroProfile Health Liveness check probe exposed at {@code /q/health/live}.
 * <p>
 * Signals UP as long as the supervisor is active and the RTSP stream is not stalled.
 * Signals DOWN (503) if the video stream freezes for over 60 seconds, prompting K8s Kubelet to evict and rebuild the Pod.
 */
@Liveness
@ApplicationScoped
public class CollectorLivenessCheck implements HealthCheck {

    private final CollectorService collectorService;

    @Inject
    public CollectorLivenessCheck(CollectorService collectorService) {
        this.collectorService = Objects.requireNonNull(collectorService, "collectorService must not be null");
    }

    @Override
    public HealthCheckResponse call() {
        boolean healthy = collectorService.isHealthy();
        StreamStatusSnapshot status = collectorService.getStatus();

        HealthCheckResponseBuilder builder = HealthCheckResponse.named("cctv-stream-liveness")
                .status(healthy)
                .withData("running", status.running())
                .withData("alive", status.alive())
                .withData("restarts", status.restartCount())
                .withData("secondsSinceLastFrame", status.secondsSinceLastFrame());

        return builder.build();
    }
}
