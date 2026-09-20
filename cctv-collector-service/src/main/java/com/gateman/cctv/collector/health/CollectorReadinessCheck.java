package com.gateman.cctv.collector.health;

import com.gateman.cctv.collector.service.CollectorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.util.Objects;

/**
 * MicroProfile Health Readiness check probe exposed at {@code /q/health/ready}.
 * <p>
 * Evaluates whether storage buffer capacity is healthy and supervisor is active before accepting gateway traffic.
 */
@Readiness
@ApplicationScoped
public class CollectorReadinessCheck implements HealthCheck {

    private final CollectorService collectorService;

    @Inject
    public CollectorReadinessCheck(CollectorService collectorService) {
        this.collectorService = Objects.requireNonNull(collectorService, "collectorService must not be null");
    }

    @Override
    public HealthCheckResponse call() {
        boolean ready = collectorService.isReady();

        HealthCheckResponseBuilder builder = HealthCheckResponse.named("cctv-collector-readiness")
                .status(ready)
                .withData("ready", ready);

        return builder.build();
    }
}
