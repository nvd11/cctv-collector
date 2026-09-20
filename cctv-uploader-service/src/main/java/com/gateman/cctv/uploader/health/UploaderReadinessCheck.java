package com.gateman.cctv.uploader.health;

import com.gateman.cctv.uploader.service.UploaderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.Objects;

@Readiness
@ApplicationScoped
public class UploaderReadinessCheck implements HealthCheck {

    private final UploaderService uploaderService;

    @Inject
    public UploaderReadinessCheck(UploaderService uploaderService) {
        this.uploaderService = Objects.requireNonNull(uploaderService, "uploaderService must not be null");
    }

    @Override
    public HealthCheckResponse call() {
        boolean ready = uploaderService.isReady();
        return HealthCheckResponse.named("cctv-uploader-readiness")
                .status(ready)
                .withData("ready", ready)
                .build();
    }
}
