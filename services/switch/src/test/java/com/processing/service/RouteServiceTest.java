package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "123456";
    private static final String RRN = "243051234567";
    private static final String ISSUER_ID = "ISS001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(
                routingService,
                authorizationClient,
                acquiringFeeClient,
                loggerClient
        );
    }

    @Test
    void tcP304_shouldRouteByBinToMatchingIssuer() {
        // Arrange
        String tcPan = "4000011234567890";
        String issuer = "ISS002";
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", STAN, tcPan, "000000",
                AMOUNT, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );
        AuthorizationResponse authResponse = approvedResponse();
        when(routingService.getIssuerIdByPan(tcPan)).thenReturn(issuer);
        when(authorizationClient.authorize(any())).thenReturn(authResponse);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(authorizationClient).authorize(argThat(req -> issuer.equals(req.issuerId())));
    }

    @Test
    void shouldReturnApprovedWhenAuthorizationAndLoggingSucceed() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse authResponse = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(authResponse);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("15"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(RRN);
        verify(loggerClient).log(any(TransactionRequest.class));
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldReturnDeclinedUnknownBinWhenRoutingFails() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        verify(loggerClient).log(any(TransactionRequest.class));
    }

    @Test
    void shouldReturnDeclinedAuthUnavailableWhenAuthorizationServiceFails() {
        // Arrange
        AuthorizationRequest request = validRequest();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException(STAN, 3, "Auth down"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        verify(loggerClient).log(any(TransactionRequest.class));
    }

    @Test
    void shouldRollbackAndReturnSystemErrorWhenQueuePublishFailsAfterApproved() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse authResponse = approvedResponse();
        RollbackResponse rollbackResponse = RollbackResponse.approved(
                new com.processing.common.dto.authorization.RollbackRequest(RRN, PAN, AMOUNT),
                Instant.now()
        );
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(authResponse);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(rollbackResponse);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    void shouldHandleNullRollbackResponseWhenRollbackCallFailsAfterQueuePublishFailure() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse authResponse = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(authResponse);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(null);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    void shouldHandleDeclinedRollbackResponseWhenRollbackIsDeclinedAfterQueuePublishFailure() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse authResponse = approvedResponse();
        RollbackResponse rollbackResponse = RollbackResponse.declined(
                new com.processing.common.dto.authorization.RollbackRequest(RRN, PAN, AMOUNT),
                "25",
                "RECORD_NOT_FOUND",
                Instant.now()
        );
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(authResponse);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(rollbackResponse);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    void shouldNotRollbackWhenQueuePublishFailsForDeclinedTransaction() {
        // Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse declinedResponse = AuthorizationResponse.declined(
                request, "INSUFFICIENT_FUNDS", "51", Instant.now()
        );
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldHandleShortPanCorrectlyWhenRouting() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", STAN, "123", "000000",
                AMOUNT, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );
        when(routingService.getIssuerIdByPan("123")).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
    }

    @Test
    void shouldHandleNullPanCorrectlyWhenRouting() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", STAN, null, "000000",
                AMOUNT, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );
        when(routingService.getIssuerIdByPan(null)).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000",
                AMOUNT, "643", Instant.now(),
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null
        );
    }

    private AuthorizationResponse approvedResponse() {
        return AuthorizationResponse.approved(
                validRequest(), RRN, "AUTH01", Instant.now()
        );
    }
}
