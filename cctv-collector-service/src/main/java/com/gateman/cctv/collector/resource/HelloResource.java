package com.gateman.cctv.collector.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Map;

@Path("/api/hello")
public class HelloResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> hello() {
        return Map.of(
            "service", "cctv-collector-service",
            "framework", "Quarkus 3.8",
            "javaVersion", System.getProperty("java.version"),
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "message", "Hello Master Jason! Cindy is ready to serve you~"
        );
    }
}
