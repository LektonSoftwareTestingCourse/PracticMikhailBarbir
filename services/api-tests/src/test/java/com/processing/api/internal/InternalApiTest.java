package com.processing.api.internal;

import com.processing.api.BaseApiTest;
import com.processing.api.support.TestDataHelper;
import com.processing.api.support.TransactionDataFactory;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

/**
 * Source: docs/api-spec.md Section 7, tz/03-switch.md, tz/04-authorization.md, tz/06-transaction-logger.md.
 */
@DisplayName("API тесты внутренних эндпоинтов и повторной доставки")
public class InternalApiTest extends BaseApiTest {
    @Test
    @DisplayName("POST /api/internal/log: первичная доставка сохраняет транзакцию (201 Created)")
    public void testInternalLog_FirstDelivery_Returns201Created() {
        // GIVEN: Уникальный ID транзакции и валидное тело
        UUID txId = UUID.randomUUID();
        String stan = TransactionDataFactory.randomStan();
        TransactionRequest request = TransactionDataFactory.loggerRequest(
                txId,
                "4000001234567890",
                BigDecimal.valueOf(10000),
                stan
        );

        // WHEN: Вызов эндпоинта логирования
        RestAssured.given()
                .spec(loggerSpec)
                .body(request)
        .when()
                .post("/api/internal/log")
        // THEN: Возвращается 201 Created
        .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", equalTo(txId.toString()))
                .body("status", equalTo("stored"));
    }

    @Test
    @DisplayName("POST /api/internal/log: повторная доставка с тем же ID и данными идемпотентна (200 OK)")
    public void testInternalLog_IdenticalReDelivery_Returns200Ok() {
        // GIVEN: Сохраняем исходную транзакцию (201 Created)
        UUID txId = UUID.randomUUID();
        String stan = TransactionDataFactory.randomStan();
        TransactionRequest request = TransactionDataFactory.loggerRequest(
                txId,
                "4000001234567890",
                BigDecimal.valueOf(10000),
                stan
        );

        RestAssured.given()
                .spec(loggerSpec)
                .body(request)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(201);

        // WHEN: Повторно доставляем РОВНО ТОТ ЖЕ запрос с тем же txId
        RestAssured.given()
                .spec(loggerSpec)
                .body(request)
        .when()
                .post("/api/internal/log")
        // THEN: Сервис не создаёт дубликат и возвращает 200 OK с существующей записью
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", equalTo(txId.toString()))
                .body("stan", equalTo(stan));
    }

    @Test
    @DisplayName("POST /api/internal/log: повторная доставка с тем же ID, но другими данными возвращает 409 Conflict")
    public void testInternalLog_ConflictingReDelivery_Returns409Conflict() {
        // GIVEN: Сохраняем транзакцию с суммой 10 000
        UUID txId = UUID.randomUUID();
        String stan = TransactionDataFactory.randomStan();
        TransactionRequest originalRequest = TransactionDataFactory.loggerRequest(
                txId,
                "4000001234567890",
                BigDecimal.valueOf(10000),
                stan
        );

        RestAssured.given()
                .spec(loggerSpec)
                .body(originalRequest)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(201);

        // Запрос с ТЕМ ЖЕ txId, но другой суммой (99 999) и другим STAN
        TransactionRequest conflictingRequest = TransactionDataFactory.loggerRequest(
                txId,
                "4000001234567890",
                BigDecimal.valueOf(99999),
                TransactionDataFactory.randomStan()
        );

        // WHEN & THEN: Должен вернуться 409 Conflict
        RestAssured.given()
                .spec(loggerSpec)
                .body(conflictingRequest)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(409);
    }

    @Test
    @DisplayName("POST /api/internal/route: вызов Switch напрямую возвращает 200 OK")
    public void testInternalRoute_Success_Returns200() {
        // GIVEN
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(5000));

        // WHEN
        RestAssured.given()
                .spec(switchSpec)
                .body(request)
        .when()
                .post("/api/internal/route")
        // THEN
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    // ДЕФЕКТ: docs/api-spec.md раздел 3 vs AuthControllerImpl; в спецификации декларирован возврат HTTP 200 со статусом DECLINED для всех исходов; во внутренней реализации сервис Authorization напрямую возвращает HTTP 404, 422, 403
    @Test
    @DisplayName("POST /api/internal/authorize: прямой вызов Authorization возвращает специфичные HTTP-статусы (404/422/403)")
    public void testInternalAuthorize_DirectStatusCodes() {
        // 1) Несуществующая карта -> HTTP 404 (в отличие от Gateway, который возвращает 200 с DECLINED)
        AuthorizationRequest notFoundRequest = TransactionDataFactory.authRequestWithIssuer("4000009999999999", BigDecimal.valueOf(1000), "ISS001");
        RestAssured.given()
                .spec(authSpec)
                .body(notFoundRequest)
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"));

        // 2) Недостаточно средств -> HTTP 422 Unprocessable Entity
        CardModel cardLowBalance = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                BigDecimal.valueOf(500),
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(100000)
        );
        AuthorizationRequest insufficientRequest = TransactionDataFactory.authRequestWithIssuer(
                cardLowBalance.pan(),
                BigDecimal.valueOf(5000),
                "ISS001"
        );
        RestAssured.given()
                .spec(authSpec)
                .body(insufficientRequest)
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(422)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"));

        // 3) Заблокированная карта -> HTTP 403 Forbidden
        TestDataHelper.updateCardStatus(gatewaySpec, cardLowBalance.pan(), CardModelStatus.BLOCKED);
        AuthorizationRequest blockedRequest = TransactionDataFactory.authRequestWithIssuer(
                cardLowBalance.pan(),
                BigDecimal.valueOf(100),
                "ISS001"
        );
        RestAssured.given()
                .spec(authSpec)
                .body(blockedRequest)
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(403)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"));
    }

    @Test
    @DisplayName("GET /api/bin/{bin}: проверка Bin Lookup (200 для известного BIN, 404 для неизвестного, 500 для fail=true)")
    public void testBinLookup_Scenarios() {
        // 1) Известный BIN 400000 -> 200 OK
        RestAssured.given()
                .spec(binLookupSpec)
        .when()
                .get("/api/bin/400000")
        .then()
                .statusCode(200)
                .body("bin", equalTo("400000"))
                .body("issuerId", equalTo("ISS001"));

        // 2) Неизвестный BIN 999999 -> 404 Not Found
        RestAssured.given()
                .spec(binLookupSpec)
        .when()
                .get("/api/bin/999999")
        .then()
                .statusCode(404);

        // 3) Симуляция сбоя ?fail=true -> 500 Internal Server Error
        RestAssured.given()
                .spec(binLookupSpec)
                .queryParam("fail", true)
        .when()
                .get("/api/bin/400000")
        .then()
                .statusCode(500);
    }
}
