package com.processing.api.support;

import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import com.processing.common.dto.cardmanagement.CreateCardRequest;
import com.processing.common.dto.cardmanagement.PatchCardRequest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.math.BigDecimal;

public final class TestDataHelper {

    private TestDataHelper() {
    }

    public static CardModel createCard(RequestSpecification spec, CreateCardRequest request) {
        return RestAssured.given()
                .spec(spec)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/cards")
                .then()
                .statusCode(201)
                .extract()
                .as(CardModel.class);
    }

    public static CardModel createDefaultCard(RequestSpecification spec) {
        return createCard(spec, CardDataFactory.defaultCardRequest());
    }

    public static CardModel createCardWithBalanceAndLimits(
            RequestSpecification spec,
            BigDecimal balance,
            BigDecimal dailyLimit,
            BigDecimal monthlyLimit) {
        return createCard(spec, CardDataFactory.cardRequest(balance, dailyLimit, monthlyLimit));
    }

    public static void updateCardStatus(RequestSpecification spec, String pan, CardModelStatus newStatus) {
        PatchCardRequest patchRequest = new PatchCardRequest(newStatus, null, null, null);
        RestAssured.given()
                .spec(spec)
                .contentType(ContentType.JSON)
                .body(patchRequest)
                .when()
                .patch("/api/cards/" + pan)
                .then()
                .statusCode(200);
    }
}
