package com.gateman.cctv.uploader.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class UploaderResourceTest {

    @Test
    @DisplayName("GET /api/uploader/status should return 200 with snapshot JSON")
    void testGetStatusEndpoint() {
        given()
                .when().get("/api/uploader/status")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("snapshotTime", notNullValue());
    }

    @Test
    @DisplayName("GET /api/uploader/tasks should return 200 with list")
    void testGetTasksEndpoint() {
        given()
                .queryParam("limit", 5)
                .when().get("/api/uploader/tasks")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @DisplayName("POST /api/uploader/trigger should trigger upload and return 200")
    void testTriggerEndpoint() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/api/uploader/trigger")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("triggered"));
    }
}
