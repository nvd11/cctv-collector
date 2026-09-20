package com.gateman.cctv.uploader.health;

import com.gateman.cctv.uploader.service.UploaderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.util.Objects;

@Liveness
@ApplicationScoped
public class UploaderLivenessCheck implements HealthCheck {

    private final UploaderService uploaderService;

    @Inject
    public UploaderLivenessCheck(UploaderService uploaderService) {
        this.uploaderService = Objects.requireNonNull(uploaderService, "uploaderService must not be null");
    }

    @Override
    public HealthCheckResponse call() {
        boolean healthy = uploaderService.isHealthy();
        return HealthCheckResponse.named("cctv-uploader-liveness")
                .status(healthy)
                .withData("healthy", healthy)
                .build();
    }
}
