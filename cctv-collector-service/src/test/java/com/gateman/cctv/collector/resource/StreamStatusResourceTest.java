package com.gateman.cctv.collector.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class StreamStatusResourceTest {

    @Test
    @DisplayName("GET /api/status should return 200 with valid telemetry JSON")
    void testGetStatusEndpoint() {
        given()
                .when().get("/api/status")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("snapshotTime", notNullValue())
                .body("restartCount", notNullValue());
    }

    @Test
    @DisplayName("GET /api/profile should return 200 with camera technical specifications")
    void testGetProfileEndpoint() {
        given()
                .when().get("/api/profile")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("protocol", equalTo("RTSP/TCP"))
                .body("videoCodec", equalTo("H.265 (HEVC)"))
                .body("fps", equalTo(15));
    }

    @Test
    @DisplayName("POST /api/restart should return 200 with restarted status")
    void testPostRestartEndpoint() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/api/restart")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("restarted"))
                .body("timestamp", notNullValue());
    }

    @Test
    @DisplayName("GET /api/segments should return 200 with JSON list")
    void testGetSegmentsEndpoint() {
        given()
                .queryParam("limit", 5)
                .when().get("/api/segments")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }
}
