package com.processing.api.support;

import com.processing.common.dto.authorization.AuthorizationRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class TransactionDataFactory {

    private TransactionDataFactory() {
    }

    public static String randomStan() {
        int stanNum = ThreadLocalRandom.current().nextInt(100000, 999999);
        return String.valueOf(stanNum);
    }

    public static AuthorizationRequest validAuthRequest(String pan, BigDecimal amount) {
        return new AuthorizationRequest(
                "0100",
                randomStan(),
                pan,
                "000000",
                amount,
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );
    }

    public static AuthorizationRequest authRequestWithStan(String pan, BigDecimal amount, String stan) {
        return new AuthorizationRequest(
                "0100",
                stan,
                pan,
                "000000",
                amount,
                "643",
                Instant.now(),
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );
    }

    public static AuthorizationRequest authRequestWithFutureDate(String pan, BigDecimal amount, Instant transmissionDateTime) {
        return new AuthorizationRequest(
                "0100",
                randomStan(),
                pan,
                "000000",
                amount,
                "643",
                transmissionDateTime,
                "TERM0001",
                null,
                "MERCH1234567890",
                "5411",
                "ACQ001",
                null
        );
    }

    public static AuthorizationRequest authRequestWithIssuer(String pan, BigDecimal amount, String issuerId) {
        return new AuthorizationRequest(
                "0100",
                randomStan(),
                pan,
                "000000",
                amount,
                "643",
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                "TERM0001",
                "POS",
                "MERCH1234567890",
                "5411",
                "ACQ001",
                issuerId
        );
    }

    public static com.processing.common.dto.transactionlogger.TransactionRequest loggerRequest(
            java.util.UUID id,
            String pan,
            BigDecimal amount,
            String stan) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return new com.processing.common.dto.transactionlogger.TransactionRequest(
                id,
                "0110",
                stan,
                "123456789012",
                pan,
                "000000",
                amount,
                "643",
                "TERM0001",
                "POS",
                "MERCH1234567890",
                "5411",
                "ACQ001",
                "ISS001",
                BigDecimal.valueOf(15),
                com.processing.common.dto.transactionlogger.TransactionStatus.APPROVED,
                null,
                "AUTH01",
                25,
                now,
                now
        );
    }
}
