package com.gateman.cctv.uploader.resource;

import com.gateman.cctv.uploader.model.UploadStatusSnapshot;
import com.gateman.cctv.uploader.model.UploadTask;
import com.gateman.cctv.uploader.service.UploaderService;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RESTful management resource for cloud upload monitoring and control.
 */
@Path("/api/uploader")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UploaderResource {

    private final UploaderService uploaderService;

    @Inject
    public UploaderResource(UploaderService uploaderService) {
        this.uploaderService = Objects.requireNonNull(uploaderService, "uploaderService must not be null");
    }

    @GET
    @Path("/status")
    public RestResponse<UploadStatusSnapshot> getStatus() {
        return RestResponse.ok(uploaderService.getStatus());
    }

    @GET
    @Path("/tasks")
    public RestResponse<List<UploadTask>> getTasks(@QueryParam("limit") @DefaultValue("10") int limit) {
        return RestResponse.ok(uploaderService.getRecentTasks(limit));
    }

    @POST
    @Path("/trigger")
    public RestResponse<Map<String, Object>> triggerUpload() {
        int processed = uploaderService.triggerUpload();
        return RestResponse.ok(Map.of(
                "status", "triggered",
                "processedCount", processed,
                "message", "Manual upload triggered successfully"
        ));
    }
}
