package com.processing.api.transactions;

import com.processing.api.BaseApiTest;
import com.processing.api.support.TestDataHelper;
import com.processing.api.support.TransactionDataFactory;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Тестирование REST-контракта поиска и экспорта транзакций (/api/transactions/search, /api/transactions/export).
 * Демонстрирует:
 * - GPath-фильтр (transactions.findAll { ... })
 * - Проверку размера массива (hasSize)
 * - Полноту по параметрам фильтрации и пагинации (включая граничные значения и пустую выборку)
 * - Проверку заголовков ответа Content-Type и Content-Disposition
 */
@DisplayName("API тесты поиска транзакций (Transaction Logger)")
public class TransactionSearchApiTest extends BaseApiTest {

    @Test
    @DisplayName("GET /api/transactions/search: фильтр по PAN с использованием GPath-фильтра")
    public void testSearch_FilterByPan_GPathFilter() {
        // GIVEN: Создаём карту и проводим транзакцию с уникальным STAN
        CardModel card = TestDataHelper.createDefaultCard(gatewaySpec);
        String stan = TransactionDataFactory.randomStan();
        AuthorizationRequest request = TransactionDataFactory.authRequestWithStan(
                card.pan(),
                BigDecimal.valueOf(10000),
                stan
        );

        RestAssured.given()
                .spec(gatewaySpec)
                .body(request)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200);

        // WHEN & THEN: Ожидаем сохранения и проверяем GPath-фильтром
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
                            .contentType(ContentType.JSON)
                            .body("total", greaterThanOrEqualTo(1))
                            .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1))
                            .body("transactions.find { it.stan == '" + stan + "' }.status", equalTo("APPROVED"))
                            .body("transactions.find { it.stan == '" + stan + "' }.pan", equalTo(card.pan()));
                });
    }

    @Test
    @DisplayName("GET /api/transactions/search: фильтрация по статусу APPROVED")
    public void testSearch_FilterByStatus_ReturnsOnlyApproved() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("status", "APPROVED")
                .queryParam("limit", 10)
                .when()
                .get("/api/transactions/search")
                // THEN
                .then()
                .statusCode(200)
                .body("transactions.every { it.status == 'APPROVED' }", equalTo(true));
    }

    @Test
    @DisplayName("GET /api/transactions/search: пустая выборка возвращает total=0 и пустой массив")
    public void testSearch_EmptyResult_ReturnsTotalZero() {
        // GIVEN: Несуществующий PAN
        String nonExistentPan = "0000000000000000";

        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("pan", nonExistentPan)
                .when()
                .get("/api/transactions/search")
                // THEN
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @Test
    @DisplayName("GET /api/transactions/search: пагинация limit и offset")
    public void testSearch_Pagination_LimitAndOffset() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 2)
                .queryParam("offset", 0)
                .when()
                .get("/api/transactions/search")
                // THEN
                .then()
                .statusCode(200)
                .body("transactions", hasSize(lessThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("GET /api/transactions/search: limit > 500 возвращает HTTP 400")
    public void testSearch_ExceedLimit_Returns400() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 501)
                .when()
                .get("/api/transactions/search")
                // THEN
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/transactions/search: отрицательный offset возвращает HTTP 400")
    public void testSearch_NegativeOffset_Returns400() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("offset", -1)
                .when()
                .get("/api/transactions/search")
                // THEN
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("GET /api/transactions/export: экспорт в CSV возвращает заголовок text/csv и файл в Content-Disposition")
    public void testExportCsv_ReturnsCsvContentType() {
        // WHEN
        RestAssured.given()
                .spec(gatewaySpec)
                .queryParam("limit", 5)
                .when()
                .get("/api/transactions/export")
                // THEN
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("transactions.csv"));
    }
}
