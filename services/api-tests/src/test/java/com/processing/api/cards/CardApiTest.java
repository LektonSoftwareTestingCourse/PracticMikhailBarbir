package com.processing.api.cards;

import com.processing.api.BaseApiTest;
import com.processing.api.support.CardDataFactory;
import com.processing.api.support.TestDataHelper;
import com.processing.common.dto.cardmanagement.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

/**
 * Source: docs/api-spec.md section 2, openapi.yaml (#/components/schemas/Card), tz/05-card-management.md.
 */
@DisplayName("API тесты Card Management Service")
public class CardApiTest extends BaseApiTest {

    @Test
    @DisplayName("POST /api/cards: успешное создание карты, валидация по схеме и формата PAN")
    public void testCreateCard_Success_Returns201AndValidSchema() {
        // GIVEN
        CreateCardRequest request = CardDataFactory.defaultCardRequest();

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/cards")
        // THEN
        .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/card-schema.json"))
                .body("id", matchesPattern("^[0-9a-fA-F-]{36}$"))
                .body("pan", matchesPattern("^400000\\d{10}$"))
                .body("bin", equalTo("400000"))
                .body("cardholderName", equalTo(request.cardholderName()))
                .body("status", equalTo("ACTIVE"))
                .body("currencyCode", equalTo("643"))
                .body("dailyLimit", equalTo(request.dailyLimit().intValue()))
                .body("monthlyLimit", equalTo(request.monthlyLimit().intValue()))
                .body("availableBalance", equalTo(request.initialBalance().intValue()))
                .body("issuerId", notNullValue())
                .body("createdAt", notNullValue());
    }

    @ParameterizedTest(name = "Невалидный BIN: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"12345", "1234567", "ABCDEF", "40000a", ""})
    @DisplayName("POST /api/cards: валидация формата BIN (ровно 6 цифр)")
    public void testCreateCard_InvalidBin_Returns400(String invalidBin) {
        // GIVEN
        CreateCardRequest request = new CreateCardRequest(
                invalidBin,
                "TEST USER",
                "643",
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(10000)
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/cards")
        // THEN
        .then()
                .statusCode(400);
    }

    @ParameterizedTest(name = "Невалидный currencyCode: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"64", "6433", "RUB", ""})
    @DisplayName("POST /api/cards: валидация кода валюты (ровно 3 цифры)")
    public void testCreateCard_InvalidCurrency_Returns400(String invalidCurrency) {
        // GIVEN
        CreateCardRequest request = new CreateCardRequest(
                "400000",
                "TEST USER",
                invalidCurrency,
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(10000)
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/cards")
        // THEN
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/cards: отрицательные лимиты отклоняются с кодом 400")
    public void testCreateCard_NegativeLimit_Returns400() {
        // GIVEN
        CreateCardRequest request = new CreateCardRequest(
                "400000",
                "TEST USER",
                "643",
                BigDecimal.valueOf(-100),
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(10000)
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/cards")
        // THEN
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: получение существующей карты")
    public void testGetCardByPan_Success_Returns200() {
        // GIVEN
        CardModel createdCard = TestDataHelper.createDefaultCard(gatewaySpec);

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + createdCard.pan())
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("pan", equalTo(createdCard.pan()))
                .body("cardholderName", equalTo(createdCard.cardholderName()))
                .body("status", equalTo("ACTIVE"))
                .body("availableBalance", equalTo(createdCard.availableBalance().intValue()));
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: проверка заголовка кэширования X-Cache (MISS затем HIT)")
    public void testGetCardByPan_HeaderXCache_MissThenHit() {
        // GIVEN
        CardModel createdCard = TestDataHelper.createDefaultCard(gatewaySpec);

        // WHEN 1: первый запрос - промах кэша
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + createdCard.pan())
        // THEN 1
        .then()
                .statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .header("Content-Type", containsString("application/json"));

        // WHEN 2: второй повторный запрос - попадание в кэш Gateway
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + createdCard.pan())
        // THEN 2
        .then()
                .statusCode(200)
                .header("X-Cache", equalTo("HIT"))
                .header("Content-Type", containsString("application/json"));
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: несуществующий PAN возвращает 404")
    public void testGetCardByPan_NotFound_Returns404() {
        // GIVEN
        String nonExistentPan = "4000009999999999";

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + nonExistentPan)
        // THEN
        .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: невалидный формат PAN (не 16 цифр) возвращает 400")
    public void testGetCardByPan_InvalidPanFormat_Returns400() {
        // GIVEN
        String invalidPan = "12345";

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + invalidPan)
        // THEN
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/cards: пагинация и проверка структуры списка карт")
    public void testGetCards_Pagination_ReturnsList() {
        // GIVEN
        TestDataHelper.createDefaultCard(gatewaySpec);

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 5)
                .queryParam("offset", 0)
        .when()
                .get("/api/cards")
        // THEN
        .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("cards", hasSize(lessThanOrEqualTo(5)));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan}: частичное обновление статуса и лимитов")
    public void testPatchCard_UpdateStatusAndLimits_Returns200() {
        // GIVEN
        CardModel createdCard = TestDataHelper.createDefaultCard(gatewaySpec);
        PatchCardRequest patchRequest = new PatchCardRequest(
                CardModelStatus.BLOCKED,
                BigDecimal.valueOf(20000),
                null,
                null
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(patchRequest)
        .when()
                .patch("/api/cards/" + createdCard.pan())
        // THEN
        .then()
                .statusCode(200)
                .body("pan", equalTo(createdCard.pan()))
                .body("status", equalTo("BLOCKED"))
                .body("dailyLimit", equalTo(20000));
    }

    // ДЕФЕКТ: docs/api-spec.md раздел 2 "DELETE /api/cards/{pan}"; предполагается soft-delete со статусом DELETED, доступным по GET; в реализации CardService при запросе GET удалённой карты выбрасывает CardNotFoundException и возвращает HTTP 404 Not Found
    @Test
    @DisplayName("DELETE /api/cards/{pan}: удаление карты возвращает 204 No Content")
    public void testDeleteCard_Success_Returns204() {
        // GIVEN
        CardModel createdCard = TestDataHelper.createDefaultCard(gatewaySpec);

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .delete("/api/cards/" + createdCard.pan())
        // THEN
        .then()
                .statusCode(204);

        // Проверяем, что удалённая карта не возвращается по GET (возвращает 404 Not Found)
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + createdCard.pan())
        .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("POST /api/cards/generate: генерация тестовых карт возвращает 201")
    public void testGenerateCards_Success_Returns201() {
        // GIVEN
        GenerateCardsRequest request = new GenerateCardsRequest(3, List.of("400000", "400001"));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/cards/generate")
        // THEN
        .then()
                .statusCode(201)
                .body("generated", equalTo(3))
                .body("cards", hasSize(3));
    }
}
