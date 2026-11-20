package com.processing.api.support;

import com.processing.common.dto.cardmanagement.CreateCardRequest;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

public final class CardDataFactory {

    private CardDataFactory() {
    }

    private static String randomLetters(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    public static CreateCardRequest defaultCardRequest() {
        return new CreateCardRequest(
                "400000",
                "TESTHOLDER " + randomLetters(8),
                "643",
                BigDecimal.valueOf(10000000),  // dailyLimit 100 000.00 RUB
                BigDecimal.valueOf(50000000),  // monthlyLimit 500 000.00 RUB
                BigDecimal.valueOf(10000000)   // initialBalance 100 000.00 RUB
        );
    }

    public static CreateCardRequest cardRequest(BigDecimal balance, BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        return new CreateCardRequest(
                "400000",
                "TESTHOLDER " + randomLetters(8),
                "643",
                dailyLimit,
                monthlyLimit,
                balance
        );
    }
}
