package com.gateman.cctv.collector.resource;

import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.model.VideoStreamProfile;
import com.gateman.cctv.collector.service.CollectorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * High-performance RESTful API resource exposing stream telemetry, technical profiles,
 * segment catalogs, and operator control actions.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StreamStatusResource {

    private final CollectorService collectorService;

    @Inject
    public StreamStatusResource(CollectorService collectorService) {
        this.collectorService = Objects.requireNonNull(collectorService, "collectorService must not be null");
    }

    /**
     * Endpoint returning a full point-in-time snapshot of stream health, framerate, and supervisor metrics.
     */
    @GET
    @Path("/status")
    public RestResponse<StreamStatusSnapshot> getStatus() {
        return RestResponse.ok(collectorService.getStatus());
    }

    /**
     * Endpoint returning the camera technical profile and connection specifications with masked credentials.
     */
    @GET
    @Path("/profile")
    public RestResponse<VideoStreamProfile> getProfile() {
        return RestResponse.ok(collectorService.getProfile());
    }

    /**
     * Operator endpoint triggering a manual stream reconnection and supervisor backoff reset.
     */
    @POST
    @Path("/restart")
    public RestResponse<Map<String, String>> restartStream() {
        collectorService.restartCollection();
        return RestResponse.ok(Map.of(
                "status", "restarted",
                "timestamp", Instant.now().toString(),
                "message", "Stream collection restart triggered successfully"
        ));
    }

    /**
     * Endpoint listing recently finalized MP4 video segments.
     *
     * @param limit maximum number of entries to return (default: 10)
     */
    @GET
    @Path("/segments")
    public RestResponse<List<VideoSegment>> listSegments(@QueryParam("limit") @DefaultValue("10") int limit) {
        return RestResponse.ok(collectorService.getRecentSegments(limit));
    }

    /**
     * Endpoint returning the most recent active or finalized video segment.
     */
    @GET
    @Path("/segments/latest")
    public RestResponse<VideoSegment> getLatestSegment() {
        return collectorService.getLatestSegment()
                .map(RestResponse::ok)
                .orElseGet(RestResponse::notFound);
    }
}
