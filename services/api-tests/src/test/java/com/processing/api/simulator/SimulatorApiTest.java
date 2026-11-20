package com.processing.api.simulator;

import com.processing.api.BaseApiTest;
import com.processing.common.dto.terminalsimulator.TerminalRunRequest;
import com.processing.common.dto.terminalsimulator.TerminalScenario;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/**
 * Source: docs/api-spec.md Section 4, openapi.yaml, tz/07-terminal-simulator.md, tz/08-merchant-acquirer.md.
 */
@DisplayName("API тесты симуляторов (Terminal & Merchant Acquirer)")
public class SimulatorApiTest extends BaseApiTest {

    @Test
    @DisplayName("POST /api/simulator/terminal/run: запуск терминального симулятора возвращает 200 и статистику")
    public void testTerminalSimulator_Run_Success() {
        // GIVEN: TerminalRunRequest требует count, scenario и tps
        TerminalRunRequest request = new TerminalRunRequest(2, TerminalScenario.normal, 10);

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/simulator/terminal/run")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("totalSubmitted", equalTo(2))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("elapsedMs", greaterThanOrEqualTo(0));
    }

    // ДЕФЕКТ: docs/api-spec.md раздел 4 "POST /api/simulator/terminal/run"; в спецификации тело запроса содержит только count и scenario; в реализации поле tps обязательно (@NotNull @Min(1)), запрос без него возвращает HTTP 400 Bad Request
    @Test
    @DisplayName("POST /api/simulator/terminal/run: отсутствие обязательного поля tps возвращает 400 (расхождение со спецификацией)")
    public void testTerminalSimulator_MissingTps_Returns400() {
        // GIVEN: Запрос в точности по спецификации docs/api-spec.md (без поля tps)
        Map<String, Object> requestWithoutTps = Map.of(
                "count", 2,
                "scenario", "normal"
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(requestWithoutTps)
        .when()
                .post("/api/simulator/terminal/run")
        // THEN: Реализация требует tps, поэтому возвращает HTTP 400
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/simulator/terminal/run: невалидный count (0) возвращает 400")
    public void testTerminalSimulator_InvalidCount_Returns400() {
        // GIVEN
        Map<String, Object> invalidRequest = Map.of(
                "count", 0,
                "scenario", "normal",
                "tps", 10
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(invalidRequest)
        .when()
                .post("/api/simulator/terminal/run")
        // THEN
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/run: запуск симулятора мерчантов возвращает 200")
    public void testMerchantSimulator_Run_Success() {
        // GIVEN
        Map<String, Object> request = Map.of(
                "count", 2,
                "scenario", "grocery",
                "tps", 10
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/simulator/merchant/run")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("totalSubmitted", equalTo(2));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/run: невалидный count (-1) возвращает 400")
    public void testMerchantSimulator_InvalidCount_Returns400() {
        // GIVEN
        Map<String, Object> invalidRequest = Map.of(
                "count", -1,
                "scenario", "grocery"
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(invalidRequest)
        .when()
                .post("/api/simulator/merchant/run")
        // THEN
        .then()
                .statusCode(400);
    }

    // ДЕФЕКТ: services/gateway/src/main/resources/application.yml vs SimulationController; эндпоинт GET /api/simulator/merchants должен быть доступен через Gateway (как и другие симуляторы); в реализации маршрут шлюза ограничен предикатом /api/simulator/merchant/**, из-за чего запрос через Gateway (:8080) возвращает 404 Not Found
    @Test
    @DisplayName("GET /api/simulator/merchants: получение списка всех мерчантов")
    public void testMerchantSimulator_GetMerchants_ReturnsList() {
        // WHEN
        RestAssured.given()
                .spec(merchantSpec)
        .when()
                .get("/api/simulator/merchants")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].id", notNullValue())
                .body("[0].mcc", notNullValue());
    }
}
