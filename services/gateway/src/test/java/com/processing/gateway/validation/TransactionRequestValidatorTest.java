package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private TransactionRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionRequestValidator();
    }

    @Test
    void shouldPassValidationWhenRequestIsValid() {
        // Arrange
        AuthorizationRequest request = validRequest();

        // Act & Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldThrowExceptionWhenRequestIsNull() {
        // Act & Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("Request body is required");
    }

    @Test
    void shouldThrowExceptionWhenMtiIsNot0100() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0200", "123456", "4000001234567890", "000000",
                new BigDecimal("100"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("mti");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "400000123456789A", "40000012345678901"})
    void shouldThrowExceptionWhenPanIsNot16Digits(String invalidPan) {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", invalidPan, "000000",
                new BigDecimal("100"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("pan");
    }

    @Test
    void shouldThrowExceptionWhenAmountIsNull() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                null, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "-0.01"})
    void shouldThrowExceptionWhenAmountIsZeroOrNegative(String invalidAmount) {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal(invalidAmount), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"64", "6433", ""})
    void shouldThrowExceptionWhenCurrencyCodeIsNot3Characters(String invalidCurrency) {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal("100"), invalidCurrency, Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("currencyCode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"541", "54111", "541A"})
    void shouldThrowExceptionWhenMccIsNot4Digits(String invalidMcc) {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal("100"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", invalidMcc, "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("mcc");
    }

    @Test
    void shouldThrowExceptionWhenTransmissionDateTimeIsNull() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal("100"), "643", null,
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );

        // Act & Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("transmissionDateTime");
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", "123456", "4000001234567890", "000000",
                new BigDecimal("100"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", "ISS001"
        );
    }
}
