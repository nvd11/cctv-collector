package com.gateman.cctv.collector.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;

@Path("/api/hello")
public class HelloResource {

    private static final Logger LOG = LoggerFactory.getLogger(HelloResource.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> hello() {
        LOG.info("Hello API invoked at {}", Instant.now());
        return Map.of(
            "service", "cctv-collector-service",
            "framework", "Quarkus 3.8",
            "javaVersion", System.getProperty("java.version"),
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "message", "Hello Master Jason! Cindy v2.0 is ready to serve you~"
        );
    }
}
