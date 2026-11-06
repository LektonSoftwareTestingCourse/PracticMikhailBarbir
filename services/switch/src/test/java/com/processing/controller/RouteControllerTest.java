package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    private RouteController routeController;

    @BeforeEach
    void setUp() {
        routeController = new RouteController(routeService);
    }

    @Test
    void shouldReturnOkResponseWhenRouteSucceeds() {
        // Arrange
        AuthorizationRequest request = createRequest("4000001234567890");
        AuthorizationResponse expectedResponse = AuthorizationResponse.approved(
                request, "123456789012", "AUTH01", Instant.now()
        );
        when(routeService.route(request)).thenReturn(expectedResponse);

        // Act
        ResponseEntity<AuthorizationResponse> response = routeController.routeTransaction(request);

        // Assert
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expectedResponse);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "123", "1234567"})
    void shouldHandleShortOrNullPanInLoggingGracefully(String pan) {
        // Arrange
        AuthorizationRequest request = createRequest(pan);
        AuthorizationResponse expectedResponse = AuthorizationResponse.unknownBin("123456");
        when(routeService.route(any())).thenReturn(expectedResponse);

        // Act
        ResponseEntity<AuthorizationResponse> response = routeController.routeTransaction(request);

        // Assert
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expectedResponse);
    }

    private AuthorizationRequest createRequest(String pan) {
        return new AuthorizationRequest(
                "0100", "123456", pan, "000000",
                new BigDecimal("100"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );
    }
}
