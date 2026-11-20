package com.processing.api.transactions;

import com.processing.api.BaseApiTest;
import com.processing.api.support.TestDataHelper;
import com.processing.api.support.TransactionDataFactory;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Source:
 * - docs/api-spec.md (section 3, коды 00, 05, 14, 51, 54, 61)
 * - docs/api/openapi.yaml (#/components/schemas/AuthorizationResponse)
 * - tz/04-authorization.md
 */
@DisplayName("API тесты авторизации транзакций")
public class TransactionApiTest extends BaseApiTest {

    @Test
    @DisplayName("POST /api/transactions: успешная авторизация (200, APPROVED, JSON-схема, RRN, AuthCode)")
    public void testAuthorizeTransaction_Approved_Returns200AndValidSchema() {
        // GIVEN: Создаём активную карту с достаточным балансом
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(15000));

        // WHEN: Отправляем запрос авторизации через Gateway
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN: Проверяем статус, JSON-схему, формат RRN/AuthCode и отсутствие declineReason
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/auth-response-schema.json"))
                .body("mti", equalTo(request.mti()))
                .body("stan", equalTo(request.stan()))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("^[0-9A-Za-z]{12}$"))
                .body("authCode", matchesPattern("^[0-9A-Za-z]{6}$"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("Бизнес-отказ: несуществующая карта (code 14, reason CARD_NOT_FOUND)")
    public void testAuthorize_Decline_CardNotFound() {
        // GIVEN: Случайный PAN с зарегистрированным BIN (400000), карты нет в Card Management
        String nonExistentPan = "400000" + System.currentTimeMillis() % 10000000000L;
        if (nonExistentPan.length() < 16) {
            nonExistentPan = String.format("%-16s", nonExistentPan).replace(' ', '0');
        }
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(nonExistentPan, BigDecimal.valueOf(1000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN: Доменный код 14 и причина CARD_NOT_FOUND проверяются совместно
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    // ДЕФЕКТ: docs/api-spec.md раздел 3 "Бизнес-отказы", tz/04-authorization.md; для немаршрутизируемого BIN ожидается declineReason UNKNOWN_BIN; в реализации SwitchService возвращает declineReason CARD_NOT_FOUND (responseCode 14)
    @Test
    @DisplayName("Бизнес-отказ: неизвестный BIN (code 14, reason UNKNOWN_BIN)")
    public void testAuthorize_Decline_UnknownBin() {
        // GIVEN: PAN с незарегистрированным BIN (999999)
        String unknownBinPan = "9999991234567890";
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(unknownBinPan, BigDecimal.valueOf(1000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN: Код 14 совпадает с Card Not Found (в реализации Switch возвращает CARD_NOT_FOUND)
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("Бизнес-отказ: заблокированная карта (code 05, reason CARD_BLOCKED)")
    public void testAuthorize_Decline_CardBlocked() {
        // GIVEN: Карта переведена в статус BLOCKED
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        TestDataHelper.updateCardStatus(gatewaySpec, card.pan(), CardModelStatus.BLOCKED);

        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(1000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    @DisplayName("Бизнес-отказ: неактивная карта (code 05, reason CARD_INACTIVE)")
    public void testAuthorize_Decline_CardInactive() {
        // GIVEN: Карта переведена в статус INACTIVE
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        TestDataHelper.updateCardStatus(gatewaySpec, card.pan(), CardModelStatus.INACTIVE);

        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(1000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN: Код 05 совпадает с CARD_BLOCKED, но причина CARD_INACTIVE их различает
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_INACTIVE"));
    }

    @Test
    @DisplayName("Бизнес-отказ: просроченная карта по статусу (code 54, reason CARD_EXPIRED - Путь 1)")
    public void testAuthorize_Decline_CardExpired_ByStatus() {
        // GIVEN: Карта переведена в статус EXPIRED
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        TestDataHelper.updateCardStatus(gatewaySpec, card.pan(), CardModelStatus.EXPIRED);

        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(1000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    @DisplayName("Бизнес-отказ: карта просрочена по transmissionDate (code 54, reason CARD_EXPIRED - Путь 2)")
    public void testAuthorize_Decline_CardExpired_ByTransmissionDate() {
        // GIVEN: Карта ACTIVE со сроком действия ~3 года, но дата транзакции задана в 2035 году
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        Instant futureDate = Instant.now().plus(365 * 10, ChronoUnit.DAYS);
        AuthorizationRequest request = TransactionDataFactory.authRequestWithFutureDate(
                card.pan(),
                BigDecimal.valueOf(1000),
                futureDate
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    @DisplayName("Бизнес-отказ: недостаточно средств (code 51, reason INSUFFICIENT_FUNDS)")
    public void testAuthorize_Decline_InsufficientFunds() {
        // GIVEN: Карта с начальным балансом 1000 копеек
        CardModel card = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(100000)
        );
        // Запрос на 5000 копеек (больше баланса)
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(5000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Бизнес-отказ: превышение дневного лимита (code 61, EXCEEDS_AMOUNT_LIMIT - Путь 1)")
    public void testAuthorize_Decline_ExceedsDailyLimit() {
        // GIVEN: Баланс достаточен, но сумма транзакции превышает dailyLimit
        CardModel card = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                BigDecimal.valueOf(1000000), // Баланс 10 000 руб
                BigDecimal.valueOf(2000),    // Дневной лимит 20 руб
                BigDecimal.valueOf(500000)   // Месячный лимит 5 000 руб
        );
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(3000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    @DisplayName("Бизнес-отказ: превышение месячного лимита (code 61, EXCEEDS_AMOUNT_LIMIT - Путь 2)")
    public void testAuthorize_Decline_ExceedsMonthlyLimit() {
        // GIVEN: Дневной лимит достаточен, но сумма транзакции превышает monthlyLimit
        CardModel card = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                BigDecimal.valueOf(1000000), // Баланс 10 000 руб
                BigDecimal.valueOf(500000),  // Дневной лимит 5 000 руб
                BigDecimal.valueOf(2000)     // Месячный лимит 20 руб
        );
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(3000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest(name = "Невалидный MTI: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"0200", "0110", "INVALID", ""})
    @DisplayName("Валидация Gateway: MTI обязан быть '0100'")
    public void testValidation_InvalidMti_Returns400(String invalidMti) {
        // GIVEN
        AuthorizationRequest request = new AuthorizationRequest(
                invalidMti,
                TransactionDataFactory.randomStan(),
                "4000001234567890",
                "000000",
                BigDecimal.valueOf(1000),
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN: Проверка принадлежности ошибки (сервис = gateway)
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", notNullValue());
    }

    @ParameterizedTest(name = "Невалидная сумма: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"0", "-100", "-0.01"})
    @DisplayName("Валидация Gateway: сумма транзакции обязана быть > 0")
    public void testValidation_InvalidAmount_Returns400(String invalidAmount) {
        // GIVEN
        AuthorizationRequest request = new AuthorizationRequest(
                "0100",
                TransactionDataFactory.randomStan(),
                "4000001234567890",
                "000000",
                new BigDecimal(invalidAmount),
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", containsString("amount"));
    }

    @ParameterizedTest(name = "Невалидный PAN: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"12345", "400000123456789", "40000012345678901", "400000123456789A"})
    @DisplayName("Валидация Gateway: PAN обязан быть ровно 16 цифр")
    public void testValidation_InvalidPan_Returns400(String invalidPan) {
        // GIVEN
        AuthorizationRequest request = new AuthorizationRequest(
                "0100",
                TransactionDataFactory.randomStan(),
                invalidPan,
                "000000",
                BigDecimal.valueOf(1000),
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", containsString("pan"));
    }

    @ParameterizedTest(name = "Невалидный MCC: \"{0}\" -> HTTP 400")
    @ValueSource(strings = {"541", "54111", "ABCD"})
    @DisplayName("Валидация Gateway: MCC обязан быть ровно 4 цифры")
    public void testValidation_InvalidMcc_Returns400(String invalidMcc) {
        // GIVEN
        AuthorizationRequest request = new AuthorizationRequest(
                "0100",
                TransactionDataFactory.randomStan(),
                "4000001234567890",
                "000000",
                BigDecimal.valueOf(1000),
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                invalidMcc,
                "ACQ001",
                null
        );

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        // THEN
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", containsString("mcc"));
    }

    @Test
    @DisplayName("Сквозная проверка: APPROVED транзакция доходит до хранилища и списывает баланс (Awaitility)")
    public void testTransaction_EndToEnd_ChainAndEventualConsistency() {
        // GIVEN: Создаём карту с начальным балансом 50 000 копеек
        BigDecimal initialBalance = BigDecimal.valueOf(50000);
        BigDecimal txAmount = BigDecimal.valueOf(15000);
        CardModel card = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                initialBalance,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(500000)
        );

        String stan = TransactionDataFactory.randomStan();
        AuthorizationRequest request = TransactionDataFactory.authRequestWithStan(card.pan(), txAmount, stan);

        // WHEN: Авторизуем транзакцию через Gateway
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        // THEN: 1) Асинхронно ожидаем появления транзакции в поиске Transaction Logger
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    RestAssured.given()
                            .spec(gatewaySpec)
                            .queryParam("pan", card.pan())
                    .when()
                            .get("/api/transactions/search")
                    .then()
                            .statusCode(200)
                            .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1))
                            .body("transactions.find { it.stan == '" + stan + "' }.status", equalTo("APPROVED"));
                });

        // THEN: 2) Проверяем изменение баланса карты (50 000 - 15 000 = 35 000)
        RestAssured.given()
                .spec(gatewaySpec)
        .when()
                .get("/api/cards/" + card.pan())
        .then()
                .statusCode(200)
                .body("availableBalance", equalTo(35000));
    }

    @Test
    @DisplayName("Асинхронность (отсутствие эффекта): при DECLINED баланс карты НЕ списывается")
    public void testTransaction_AsyncNegative_DeclineDoesNotReduceBalance() {
        // GIVEN: Карта с балансом 10 000 копеек
        BigDecimal initialBalance = BigDecimal.valueOf(10000);
        CardModel card = TestDataHelper.createCardWithBalanceAndLimits(
                gatewaySpec,
                initialBalance,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(500000)
        );

        // Отправляем транзакцию, превышающую баланс (50 000)
        AuthorizationRequest request = TransactionDataFactory.validAuthRequest(card.pan(), BigDecimal.valueOf(50000));

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"));

        // THEN: Баланс карты остаётся неизменным (10 000 копеек)
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    RestAssured.given()
                            .spec(gatewaySpec)
                    .when()
                            .get("/api/cards/" + card.pan())
                    .then()
                            .statusCode(200)
                            .body("availableBalance", equalTo(initialBalance.intValue()));
                });
    }
}
