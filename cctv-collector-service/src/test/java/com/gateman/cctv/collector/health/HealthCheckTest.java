package com.gateman.cctv.collector.health;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class HealthCheckTest {

    @Test
    @DisplayName("GET /q/health/live should respond with liveness probe data")
    void testLivenessCheck() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", notNullValue())
                .body(containsString("cctv-stream-liveness"));
    }

    @Test
    @DisplayName("GET /q/health/ready should respond with readiness probe data")
    void testReadinessCheck() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", notNullValue())
                .body(containsString("cctv-collector-readiness"));
    }
}
