package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "input={0} -> expected={1}")
    @CsvSource(value = {
            "TERM0001, TERM0001",
            "TERM1234567, TERM1234",
            "TERM, TERM0000",
            "'', ''",
            "'   ', '   '"
    })
    void shouldNormalizeTerminalIdCorrectly(String input, String expected) {
        // Arrange & Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldReturnNullWhenTerminalIdIsNull() {
        // Arrange & Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(null);

        // Assert
        assertThat(result).isNull();
    }

    @ParameterizedTest(name = "input={0} -> expected={1}")
    @CsvSource(value = {
            "MERCH0000000002, MERCH0000000002",
            "MERCH00000000002, MERCH0000000002",
            "OTHER12345678901, OTHER1234567890",
            "MERCH12345678901, MERCH1234567890",
            "MERCH1234567890EXTRA, MERCH1234567890",
            "MERCH123, MERCH1230000000",
            "'', ''",
            "'   ', '   '"
    })
    void shouldNormalizeMerchantIdCorrectly(String input, String expected) {
        // Arrange & Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldReturnNullWhenMerchantIdIsNull() {
        // Arrange & Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(null);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldNormalizeEntireAuthorizationRequest() {
        // Arrange
        AuthorizationRequest rawRequest = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal("500"), "643", Instant.now(),
                "TERM", "POS", "MERCH123", "5411", "ACQ001", null
        );

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(rawRequest);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("TERM0000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH1230000000");
        assertThat(normalized.stan()).isEqualTo("123456");
    }
}
