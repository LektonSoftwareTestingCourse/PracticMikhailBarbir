package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "123456";

    @Mock
    private RestClient restClient;

    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private AuthorizationClient authorizationClient;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001"),
                "http://localhost:8082",
                "http://localhost:8083",
                "http://localhost:8084",
                null,
                new SwitchProperties.RetryProperties(2, List.of(10L)),
                null
        );
        circuitBreaker = CircuitBreaker.ofDefaults("testCb");
        retry = Retry.ofDefaults("testRetry");
        authorizationClient = new AuthorizationClient(
                properties,
                restClient,
                retry,
                circuitBreaker
        );
    }

    @Test
    void shouldReturnApprovedWhenAuthorizeCallSucceeds() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse expected = AuthorizationResponse.approved(
                request, "243051234567", "AUTH01", Instant.now()
        );

        setupPostMocks(expected);

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.authCode()).isEqualTo("AUTH01");
    }

    @Test
    void shouldReturnDeclinedWhenAuthorizeCallReturnsDeclined() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse expected = AuthorizationResponse.declined(
                request, "INSUFFICIENT_FUNDS", "51", Instant.now()
        );

        setupPostMocks(expected);

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request);

                // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenStatusIsInvalid() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse invalid = new AuthorizationResponse(
                "0110", STAN, "243051234567", null, "00", "PENDING", null, 10
        );

        setupPostMocks(invalid);

        // Act & Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenCircuitBreakerIsOpen() {
        // Arrange
        AuthorizationRequest request = validRequest();
        circuitBreaker.transitionToOpenState();

        // Act & Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenServerReturnsInvalidBody() {
        // Arrange
        AuthorizationRequest request = validRequest();
        setupPostMocks(null);

        // Act & Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldReturnRollbackResponseWhenRollbackCallSucceeds() {
        // Arrange
        AuthorizationRequest request = validRequest();
        RollbackResponse expected = RollbackResponse.approved(
                new com.processing.common.dto.authorization.RollbackRequest("243051234567", PAN, new BigDecimal("1000")),
                Instant.now()
        );
        setupPostRollbackMocks(expected);

        // Act
        RollbackResponse response = authorizationClient.rollback(request, "243051234567");

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldReturnRollbackResponseWhenRollbackReturnsDeclined() {
        // Arrange
        AuthorizationRequest request = validRequest();
        RollbackResponse expected = RollbackResponse.declined(
                new com.processing.common.dto.authorization.RollbackRequest("243051234567", PAN, new BigDecimal("1000")),
                "25",
                "RECORD_NOT_FOUND",
                Instant.now()
        );
        setupPostRollbackMocks(expected);

        // Act
        RollbackResponse response = authorizationClient.rollback(request, "243051234567");

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("DECLINED");
    }

    @Test
    void shouldReturnNullWhenRollbackReturnsInvalidStatus() {
        // Arrange
        AuthorizationRequest request = validRequest();
        RollbackResponse invalid = new RollbackResponse(
                "243051234567", "00", "UNKNOWN", null, 10
        );
        setupPostRollbackMocks(invalid);

        // Act
        RollbackResponse response = authorizationClient.rollback(request, "243051234567");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackFailsWithException() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(restClient.post()).thenThrow(new RuntimeException("Rollback endpoint error"));

        // Act
        RollbackResponse response = authorizationClient.rollback(request, "243051234567");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnOkWhenHealthCheckSucceeds() {
        // Arrange
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReturnDownWhenHealthCheckFails() {
        // Arrange
        when(restClient.get()).thenThrow(new RuntimeException("Service down"));

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    private void setupPostMocks(AuthorizationResponse response) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(response);
    }

    private void setupPostRollbackMocks(RollbackResponse response) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(RollbackResponse.class)).thenReturn(response);
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000",
                new BigDecimal("1000"), "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", "ISS001"
        );
    }
}
