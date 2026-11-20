package com.processing.api.health;

import com.processing.api.BaseApiTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/**
 * Source: docs/api-spec.md Section 1, openapi.yaml (#/paths/~1health), tz/01-devops.md.
 */
@DisplayName("API тесты Health Check")
public class HealthApiTest extends BaseApiTest {

    @Test
    @DisplayName("GET /health: Gateway возвращает 200 OK и статус всех downstream-сервисов")
    public void testGetHealth_Returns200AndAllServicesOk() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/health")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", equalTo("1.0.0"))
                .body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }
}
