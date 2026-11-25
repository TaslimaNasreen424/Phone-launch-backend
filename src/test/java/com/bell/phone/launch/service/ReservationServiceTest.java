package com.bell.phone.launch.service;

import com.bell.phone.launch.dto.CompletePurchaseRequest;
import com.bell.phone.launch.dto.CompletePurchaseResponse;
import com.bell.phone.launch.dto.ReservePhoneRequest;
import com.bell.phone.launch.dto.ReservePhoneResponse;
import com.bell.phone.launch.entity.Inventory;
import com.bell.phone.launch.entity.Product;
import com.bell.phone.launch.entity.PurchaseHistory;
import com.bell.phone.launch.entity.Reservation;
import com.bell.phone.launch.exception.InsufficientInventoryException;
import com.bell.phone.launch.exception.ReservationExpiredException;
import com.bell.phone.launch.repository.InventoryRepository;
import com.bell.phone.launch.repository.ProductRepository;
import com.bell.phone.launch.repository.PurchaseHistoryRepository;
import com.bell.phone.launch.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private PurchaseHistoryRepository purchaseHistoryRepository;

    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(productRepository, inventoryRepository,
            reservationRepository, purchaseHistoryRepository);
    }

    private ReservePhoneRequest reserveRequest(int quantity) {
        return ReservePhoneRequest.builder().productId(1L).customerId("Taslima")
            .quantity(quantity).sessionToken("session").deviceFingerprint("device").build();
    }

    private Reservation reservation(String customer, Reservation.ReservationStatus status,
                                    LocalDateTime expiresAt) {
        return Reservation.builder().reservationId(1L).productId(1L).customerId(customer)
            .quantity(2).status(status).expiresAt(expiresAt).build();
    }

    private Inventory inventory(long available, long reserved, long sold) {
        return Inventory.builder().inventoryId(1L).productId(1L).totalUnits(10L)
            .availableUnits(available).reservedUnits(reserved).soldUnits(sold).build();
    }

    // A successful reservation holds two units and expires approximately 15 minutes later.
    @Test
    void reserveMovesAvailableUnitsToReserved() {
        Inventory inventory = inventory(10, 0, 0);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.builder().productId(1L).build()));
        when(reservationRepository.findAll()).thenReturn(List.of());
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inventory));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            saved.setReservationId(7L);
            return saved;
        });

        LocalDateTime before = LocalDateTime.now();
        ReservePhoneResponse response = service.reservePhone(reserveRequest(2));
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertEquals(7L, response.getReservationId());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("Taslima", saved.getValue().getCustomerId());
        assertEquals(2, saved.getValue().getQuantity());
        assertFalse(saved.getValue().getExpiresAt().isBefore(before.plusMinutes(15)));
        assertFalse(saved.getValue().getExpiresAt().isAfter(after.plusMinutes(15)));
        assertEquals(8L, inventory.getAvailableUnits());
        assertEquals(2L, inventory.getReservedUnits());
        verify(inventoryRepository).save(inventory);
    }

    // An unknown product is rejected before reservations or inventory are accessed.
    @Test
    void reserveRejectsUnknownProduct() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.reservePhone(reserveRequest(1)));
        verifyNoInteractions(reservationRepository, inventoryRepository);
    }

    // Asking for more units than available must not save a reservation or change stock.
    @Test
    void reserveRejectsInsufficientStockWithoutSaving() {
        Inventory inventory = inventory(1, 0, 9);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.builder().build()));
        when(reservationRepository.findAll()).thenReturn(List.of());
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inventory));

        assertThrows(InsufficientInventoryException.class, () -> service.reservePhone(reserveRequest(2)));
        assertEquals(1L, inventory.getAvailableUnits());
        assertEquals(0L, inventory.getReservedUnits());
        verify(reservationRepository, never()).save(any());
        verify(inventoryRepository, never()).save(any());
    }

    // Five active, unexpired reservations for one customer block another reservation.
    @Test
    void reserveRejectsFiveActiveReservationsForSameCustomer() {
        Reservation active = reservation("Taslima", Reservation.ReservationStatus.ACTIVE,
            LocalDateTime.now().plusMinutes(1));
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.builder().build()));
        when(reservationRepository.findAll()).thenReturn(List.of(active, active, active, active, active));

        assertThrows(IllegalArgumentException.class, () -> service.reservePhone(reserveRequest(1)));
        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).save(any());
    }

    // Other customers' reservations and completed or expired holds do not count toward the limit.
    @Test
    void reserveDoesNotCountOtherCustomersCompletedOrExpiredReservations() {
        LocalDateTime future = LocalDateTime.now().plusMinutes(5);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.builder().build()));
        when(reservationRepository.findAll()).thenReturn(List.of(
            reservation("Other", Reservation.ReservationStatus.ACTIVE, future),
            reservation("Taslima", Reservation.ReservationStatus.COMPLETED, future),
            reservation("Taslima", Reservation.ReservationStatus.ACTIVE, LocalDateTime.now().minusMinutes(1))));
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inventory(10, 0, 0)));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("ACTIVE", service.reservePhone(reserveRequest(1)).getStatus());
    }

    // Completing a valid reservation moves its units from reserved to sold.
    @Test
    void purchaseMovesReservedUnitsToSoldAndCompletesReservation() {
        Reservation reservation = reservation("Taslima", Reservation.ReservationStatus.ACTIVE,
            LocalDateTime.now().plusMinutes(5));
        Inventory inventory = inventory(8, 2, 0);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inventory));
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.builder().price(100.0).build()));
        when(purchaseHistoryRepository.save(any(PurchaseHistory.class))).thenAnswer(invocation -> {
            PurchaseHistory saved = invocation.getArgument(0);
            saved.setPurchaseId(9L);
            return saved;
        });

        CompletePurchaseResponse response = service.completePurchase(
            CompletePurchaseRequest.builder().reservationId(1L).customerId("Taslima").build());

        assertEquals(9L, response.getPurchaseId());
        assertEquals(200.0, response.getTotalPrice());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(8L, inventory.getAvailableUnits());
        assertEquals(0L, inventory.getReservedUnits());
        assertEquals(2L, inventory.getSoldUnits());
        assertEquals(Reservation.ReservationStatus.COMPLETED, reservation.getStatus());
        assertNotNull(reservation.getCompletedAt());
        verify(inventoryRepository).save(inventory);
        verify(reservationRepository).save(reservation);
    }

    // An expired hold cannot be purchased and must not change inventory.
    @Test
    void purchaseRejectsExpiredReservationWithoutChangingInventory() {
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(
            reservation("Taslima", Reservation.ReservationStatus.ACTIVE, LocalDateTime.now().minusMinutes(1))));

        assertThrows(ReservationExpiredException.class, () -> service.completePurchase(
            CompletePurchaseRequest.builder().reservationId(1L).customerId("Taslima").build()));
        verifyNoInteractions(inventoryRepository, purchaseHistoryRepository);
    }

    // A customer ID that differs from the reservation's ID cannot complete it.
    @Test
    void purchaseRejectsDifferentCustomerWithoutChangingInventory() {
        Reservation reservation = reservation("Taslima", Reservation.ReservationStatus.ACTIVE,
            LocalDateTime.now().plusMinutes(5));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.completePurchase(
            CompletePurchaseRequest.builder().reservationId(1L).customerId("Shahid").build()));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertEquals(Reservation.ReservationStatus.ACTIVE, reservation.getStatus());
        verifyNoInteractions(inventoryRepository, purchaseHistoryRepository);
    }

    // An already completed reservation cannot be purchased again.
    @Test
    void purchaseRejectsCompletedReservation() {
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(
            reservation("Taslima", Reservation.ReservationStatus.COMPLETED, LocalDateTime.now().plusMinutes(5))));

        assertThrows(RuntimeException.class, () -> service.completePurchase(
            CompletePurchaseRequest.builder().reservationId(1L).customerId("Taslima").build()));
        verifyNoInteractions(inventoryRepository, purchaseHistoryRepository);
    }

    // Explicit cleanup releases only expired active holds, leaving other reservations unchanged.
    @Test
    void releaseExpiredReservationsReturnsOnlyExpiredActiveStock() {
        Reservation expired = reservation("Taslima", Reservation.ReservationStatus.ACTIVE,
            LocalDateTime.now().minusMinutes(1));
        Reservation stillActive = reservation("Other", Reservation.ReservationStatus.ACTIVE,
            LocalDateTime.now().plusMinutes(5));
        Reservation completed = reservation("Third", Reservation.ReservationStatus.COMPLETED,
            LocalDateTime.now().minusMinutes(1));
        Inventory inventory = inventory(6, 4, 0);
        when(reservationRepository.findAll()).thenReturn(List.of(expired, stillActive, completed));
        when(inventoryRepository.findById(1L)).thenReturn(Optional.of(inventory));

        service.releaseExpiredReservations();

        assertEquals(8L, inventory.getAvailableUnits());
        assertEquals(2L, inventory.getReservedUnits());
        assertEquals(Reservation.ReservationStatus.EXPIRED, expired.getStatus());
        assertEquals(Reservation.ReservationStatus.ACTIVE, stillActive.getStatus());
        assertEquals(Reservation.ReservationStatus.COMPLETED, completed.getStatus());
        verify(reservationRepository).save(expired);
        verify(inventoryRepository).save(inventory);
    }

    // The stock response calculates sold percentage from sold units and total units.
    @Test
    void inventoryStatusReportsSoldPercentage() {
        when(inventoryRepository.findAll()).thenReturn(List.of(inventory(6, 2, 2)));
        when(productRepository.findById(1L)).thenReturn(Optional.of(
            Product.builder().productId(1L).sku("SKU").build()));

        var status = service.getInventoryStatus();
        assertEquals(1, status.size());
        assertEquals("SKU", status.get(0).getSku());
        assertEquals(6L, status.get(0).getAvailableUnits());
        assertEquals(20.0, status.get(0).getSoldPercentage(), 0.001);
    }
}
