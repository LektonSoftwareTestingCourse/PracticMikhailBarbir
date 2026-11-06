package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0",
                Map.of(
                        "400000", "ISS001",
                        "510000", "ISS002"
                ),
                "http://localhost:8082",
                "http://localhost:8083",
                "http://localhost:8084",
                null,
                new SwitchProperties.RetryProperties(3, List.of(100L)),
                null
        );
        routingService = new RoutingService(properties);
    }

    @Test
    void shouldReturnIssuerIdWhenPanHasKnownBin() {
        // Arrange
        String pan = "4000001234567890";

        // Act
        String issuerId = routingService.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuerId).isEqualTo("ISS001");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "123", "12345"})
    void shouldThrowUnknownBinExceptionWhenPanIsNullOrEmptyOrTooShort(String pan) {
        // Arrange & Act & Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    void shouldThrowUnknownBinExceptionWhenBinIsNotFoundInTable() {
        // Arrange
        String pan = "9999991234567890";

        // Act & Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("999999");
    }
}
