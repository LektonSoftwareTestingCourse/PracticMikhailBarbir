package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567893";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "123456789012";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                retryService,
                transactionRunner
        );
    }

    @Test
    void shouldTransitionActiveToBlockedWhenPatchCard() {
        // Arrange
        Card activeCard = sampleActiveCard();
        Card blockedCard = activeCard.withData(
                CardStatus.BLOCKED,
                activeCard.dailyLimit(),
                activeCard.monthlyLimit(),
                activeCard.availableBalance()
        );
        when(transactionRunner.runSupplier(any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard));
        when(cardRepository.update(any())).thenReturn(blockedCard);

        // Act
        Card result = cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
        verify(cardRepository).update(any(Card.class));
        verify(eventNotifier).notifyListeners(any(CardServicePatchEvent.class));
    }

    @Test
    void shouldCreateCardWhenInputIsValid() {
        // Arrange
        Card card = sampleActiveCard();
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(settings.cardValidityPeriod()).thenReturn(36);
        when(settings.maxCardCreationRetries()).thenReturn(3);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(retryService.supply(eq(3), any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(1);
            return supplier.get();
        });
        when(cardRepository.create(any())).thenReturn(card);

        // Act
        Card created = cardService.createCard(
                BIN,
                "IVAN IVANOV",
                "643",
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                new BigDecimal("200000")
        );

        // Assert
        assertThat(created).isNotNull();
        assertThat(created.pan()).isEqualTo(PAN);
        verify(eventNotifier).notifyListeners(any(CardServiceCreationEvent.class));
    }

    @Test
    void shouldGetCardWhenPanExists() {
        // Arrange
        Card card = sampleActiveCard();
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        // Act
        Card result = cardService.getCard(PAN);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.pan()).isEqualTo(PAN);
    }

    @Test
    void shouldThrowCardNotFoundExceptionWhenPanDoesNotExist() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> cardService.getCard(PAN))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldReserveFundsWhenSufficientBalance() {
        // Arrange
        Card card = sampleActiveCard();
        when(transactionRunner.runSupplier(any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Card result = cardService.reserve(PAN, AMOUNT, RRN);

        // Assert
        assertThat(result).isNotNull();
        verify(reservationRepository).save(any(Reservation.class));
        verify(eventNotifier).notifyListeners(any(CardServiceReserveEvent.class));
    }

    @Test
    void shouldRollbackReservationWhenReservationExists() {
        // Arrange
        Card card = sampleActiveCard();
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);
        when(transactionRunner.runSupplier(any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Card result = cardService.rollback(PAN, AMOUNT, RRN);

        // Assert
        assertThat(result).isNotNull();
        verify(reservationRollbackRepository).save(any());
        verify(eventNotifier).notifyListeners(any(CardServiceRollbackEvent.class));
    }

    private Card sampleActiveCard() {
        return new Card(
                UUID.randomUUID(),
                PAN,
                BIN,
                "IVAN IVANOV",
                YearMonth.of(2028, 12),
                CardStatus.ACTIVE,
                "643",
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                new BigDecimal("200000"),
                ISSUER_ID,
                Instant.now()
        );
    }
}
