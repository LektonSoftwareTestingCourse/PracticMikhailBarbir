package com.processing.api.dashboard;

import com.processing.api.BaseApiTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

/**
 * Source: docs/api-spec.md Section 5, openapi.yaml, tz/09-web-dashboard.md.
 */
@DisplayName("API тесты Dashboard")
public class DashboardApiTest extends BaseApiTest {

    @Test
    @DisplayName("GET /api/dashboard/stats: получение агрегированной статистики")
    public void testGetStats_Returns200AndValidMetrics() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/dashboard/stats")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0))
                .body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", greaterThanOrEqualTo(0.0f));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent: получение списка последних транзакций с пагинацией")
    public void testGetRecent_Returns200AndList() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 10)
        .when()
                .get("/api/dashboard/recent")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(lessThanOrEqualTo(10)));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent: невалидный limit (> 500) возвращает 400")
    public void testGetRecent_InvalidLimit_Returns400() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 501)
        .when()
                .get("/api/dashboard/recent")
        // THEN
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/dashboard/charts: получение корзин для графиков")
    public void testGetCharts_Returns200() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("granularity", "hour")
        .when()
                .get("/api/dashboard/charts")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }
}
