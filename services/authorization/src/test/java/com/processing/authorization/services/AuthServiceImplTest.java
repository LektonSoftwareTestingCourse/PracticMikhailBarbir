package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final Instant REQUEST_TIME = Instant.parse("2026-11-05T12:00:00Z");

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient
        );
    }

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
        // Arrange
        CardModel card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isNotBlank();
        assertThat(response.authCode()).isNotBlank();
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenDailyLimitExceeded() {
        // Arrange
        CardModel card = cardWithLimits(new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("200000"));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(new BigDecimal("10001")), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("61");
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenBalanceExceeded() {
        // Arrange
        CardModel card = cardWithLimits(new BigDecimal("500000"), new BigDecimal("500000"), new BigDecimal("200000"));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(new BigDecimal("200001")), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenPanNotFound() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest
    @CsvSource({
            "INACTIVE, 05, CARD_INACTIVE",
            "BLOCKED,  05, CARD_BLOCKED",
            "EXPIRED,  54, CARD_EXPIRED"
    })
    void shouldDeclineWhenCardStatusNotActive(
            CardModelStatus status,
            String expectedCode,
            String expectedReason
    ) {
        // Arrange
        CardModel card = cardWithStatus(status);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenExpiryDatePassed() {
        // Arrange
        CardModel card = cardWithExpiry(YearMonth.of(2025, 12));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("54");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenLimitsExceeded() {
        // Arrange
        CardModel card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("61");
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalance() {
        // Arrange
        CardModel card = cardWithBalance(new BigDecimal("500"));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(new BigDecimal("1000")), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest
    @CsvSource({
            "1, APPROVED, 00",
            "0, DECLINED, 61",
            "1, APPROVED, 00",
            "1, APPROVED, 00",
            "0, DECLINED, 61"
    })
    void shouldLimitBoundariesCorrectly(
            int upsertResult,
            String expectedStatus,
            String expectedCode
    ) {
        // Arrange
        CardModel card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(upsertResult);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(expectedStatus);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveWhenAmountEqualsBalance() {
        // Arrange
        BigDecimal balance = new BigDecimal("1000");
        CardModel card = cardWithBalance(balance);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(balance), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenCardExpired() {
        // Arrange
        CardModel card = cardWithExpiry(YearMonth.of(2026, 10));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("54");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveWhenCardExpiresAtEndOfCurrentMonth() {
        // Arrange
        CardModel card = cardWithExpiry(YearMonth.of(2026, 11));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "{0}: status={1}, expPassed={2}, balOk={3}, limOk={4} -> status={5}, code={6}")
    @CsvSource({
            "TC-PW-01, EXPIRED,  false, true,  true,  DECLINED, 54, CARD_EXPIRED",
            "TC-PW-02, BLOCKED,  false, true,  true,  DECLINED, 05, CARD_BLOCKED",
            "TC-PW-03, INACTIVE, false, true,  true,  DECLINED, 05, CARD_INACTIVE",
            "TC-PW-04, INACTIVE, true,  true,  true,  DECLINED, 05, CARD_INACTIVE",
            "TC-PW-05, ACTIVE,   false, false, false, DECLINED, 51, INSUFFICIENT_FUNDS",
            "TC-PW-06, INACTIVE, false, true,  true,  DECLINED, 05, CARD_INACTIVE",
            "TC-PW-07, INACTIVE, false, true,  true,  DECLINED, 05, CARD_INACTIVE",
            "TC-PW-08, EXPIRED,  true,  true,  true,  DECLINED, 54, CARD_EXPIRED",
            "TC-PW-09, BLOCKED,  true,  true,  true,  DECLINED, 05, CARD_BLOCKED",
            "TC-PW-10, ACTIVE,   false, true,  false, DECLINED, 61, EXCEEDS_AMOUNT_LIMIT",
            "TC-PW-11, BLOCKED,  false, true,  true,  DECLINED, 05, CARD_BLOCKED",
            "TC-PW-12, ACTIVE,   false, true,  false, DECLINED, 61, EXCEEDS_AMOUNT_LIMIT",
            "TC-PW-13, ACTIVE,   false, true,  true,  APPROVED, 00, NONE",
            "TC-PW-14, ACTIVE,   false, false, true,  DECLINED, 51, INSUFFICIENT_FUNDS",
            "TC-PW-15, ACTIVE,   false, true,  false, DECLINED, 61, EXCEEDS_AMOUNT_LIMIT",
            "TC-PW-16, ACTIVE,   false, false, true,  DECLINED, 51, INSUFFICIENT_FUNDS",
            "TC-PW-17, ACTIVE,   false, true,  true,  APPROVED, 00, NONE",
            "TC-PW-18, EXPIRED,  false, true,  true,  DECLINED, 54, CARD_EXPIRED",
            "TC-PW-19, ACTIVE,   false, true,  true,  APPROVED, 00, NONE",
            "TC-PW-20, ACTIVE,   false, false, false, DECLINED, 51, INSUFFICIENT_FUNDS",
            "TC-PW-21, BLOCKED,  true,  true,  true,  DECLINED, 05, CARD_BLOCKED",
            "TC-PW-22, ACTIVE,   false, true,  false, DECLINED, 61, EXCEEDS_AMOUNT_LIMIT",
            "TC-PW-23, ACTIVE,   false, true,  false, DECLINED, 61, EXCEEDS_AMOUNT_LIMIT",
            "TC-PW-24, EXPIRED,  false, true,  true,  DECLINED, 54, CARD_EXPIRED",
            "TC-PW-25, ACTIVE,   true,  true,  true,  DECLINED, 54, CARD_EXPIRED"
    })
    void pairwisePriority(
            String testId,
            CardModelStatus status,
            boolean isExpiredByDate,
            boolean isBalanceSufficient,
            boolean isLimitsSufficient,
            String expectedStatus,
            String expectedCode,
            String expectedReason
    ) {
        // Arrange
        YearMonth expiry = isExpiredByDate ? YearMonth.of(2025, 1) : YearMonth.of(2028, 12);
        BigDecimal balance = isBalanceSufficient ? new BigDecimal("100000") : new BigDecimal("100");
        CardModel card = new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiry, status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), balance,
                ISSUER_ID, Instant.now()
        );
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        if (status == CardModelStatus.ACTIVE && !isExpiredByDate && isBalanceSufficient) {
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenReturn(isLimitsSufficient ? 1 : 0);
        }

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(expectedStatus);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        if (!"NONE".equals(expectedReason)) {
            assertThat(response.declineReason()).isEqualTo(expectedReason);
        }
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineByBalanceBeforeCheckingLimits() {
        // Arrange
        CardModel card = cardWithBalance(new BigDecimal("999.99"));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(new BigDecimal("1000.00")), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("51");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(limitUsageRepository, never()).upsertLimitUsage(any(), any(), any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenMonthlyLimitExceededWhileDailyIsOk() {
        // Arrange
        CardModel card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("61");
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void expiredCardDominatesOverLimitsAndBalance() {
        // Arrange
        CardModel card = cardWithStatus(CardModelStatus.EXPIRED);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(AMOUNT), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("54");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        verify(limitUsageRepository, never()).upsertLimitUsage(any(), any(), any(), any(), any());
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    private AuthorizationRequest validRequest(BigDecimal amount) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                amount, "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
        );
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.of(2028, 12), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("200000"),
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithExpiry(YearMonth expiryDate) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiryDate, CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("200000"),
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithBalance(BigDecimal balance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.of(2028, 12), CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), balance,
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithLimits(BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal balance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.of(2028, 12), CardModelStatus.ACTIVE, "643",
                dailyLimit, monthlyLimit, balance,
                ISSUER_ID, Instant.now()
        );
    }
}
