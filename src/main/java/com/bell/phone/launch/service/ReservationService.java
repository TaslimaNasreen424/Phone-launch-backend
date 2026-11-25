package com.bell.phone.launch.service;

import com.bell.phone.launch.dto.*;
import com.bell.phone.launch.entity.*;
import com.bell.phone.launch.exception.*;
import com.bell.phone.launch.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ReservationService - Core business logic for phone reservations
 * 
 * Key design decisions:
 * 1. Uses optimistic locking via JPA @Version to prevent race conditions
 * 2. Atomic operations via @Transactional at method level
 * 3. Reservation timeout: 15 minutes (balances customer checkout time vs inventory turnover)
 * 4. Fraud prevention: max 5 active reservations per customer
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReservationService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final PurchaseHistoryRepository purchaseHistoryRepository;

    private static final int RESERVATION_TIMEOUT_MINUTES = 15;
    private static final int MAX_ACTIVE_RESERVATIONS_PER_CUSTOMER = 5;
    private static final DateTimeFormatter DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Reserve a phone for a customer
     * 
     * Flow:
     * 1. Validate product exists
     * 2. Check customer fraud indicators (max active reservations)
     * 3. Check inventory availability
     * 4. Create reservation record
     * 5. Update inventory (reserved units +1, available units -1)
     * 
     * @throws InsufficientInventoryException if stock exhausted
     */
    public ReservePhoneResponse reservePhone(ReservePhoneRequest request) {
        log.info("Reserve phone requested: productId={}, customerId={}, qty={}", 
            request.getProductId(), request.getCustomerId(), request.getQuantity());

        // Validate product exists
        Product product = productRepository.findById(request.getProductId())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + request.getProductId()));

        try {
            // Check active reservation count per customer (fraud prevention)
            long activeReservationCount = reservationRepository.findAll().stream()
                .filter(r -> r.getCustomerId().equals(request.getCustomerId()) 
                    && Reservation.ReservationStatus.ACTIVE.equals(r.getStatus())
                    && r.getExpiresAt().isAfter(LocalDateTime.now()))
                .count();
            
            if (activeReservationCount >= MAX_ACTIVE_RESERVATIONS_PER_CUSTOMER) {
                throw new IllegalArgumentException("You have too many active reservations. Please complete or cancel an existing reservation.");
            }

            // Get inventory and check availability
            Inventory inventory = inventoryRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("Inventory not found for product: " + request.getProductId()));

            if (inventory.getAvailableUnits() < request.getQuantity()) {
                throw new InsufficientInventoryException("Sorry! This model is out of stock.");
            }

            // Create reservation
            Reservation reservation = Reservation.builder()
                .productId(request.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .status(Reservation.ReservationStatus.ACTIVE)
                .reservedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TIMEOUT_MINUTES))
                .sessionToken(request.getSessionToken())
                .deviceFingerprint(request.getDeviceFingerprint())
                .build();
            
            reservation = reservationRepository.save(reservation);
            Long reservationId = reservation.getReservationId();

            // Update inventory (reserve units)
            inventory.setReservedUnits(inventory.getReservedUnits() + request.getQuantity());
            inventory.setAvailableUnits(inventory.getAvailableUnits() - request.getQuantity());
            inventory.setLastUpdated(LocalDateTime.now());
            inventoryRepository.save(inventory);

            log.info("Reservation created: id={}, expiresAt={}", reservationId, reservation.getExpiresAt());

            return ReservePhoneResponse.builder()
                .reservationId(reservationId)
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .status("ACTIVE")
                .expiresAt(reservation.getExpiresAt().format(DATE_FORMATTER))
                .message(String.format("Phone reserved successfully. Valid for %d minutes.", RESERVATION_TIMEOUT_MINUTES))
                .build();

        } catch (InsufficientInventoryException | IllegalArgumentException e) {
            log.warn("Reservation validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error during reservation", e);
            throw new RuntimeException("Failed to reserve phone: " + e.getMessage(), e);
        }
    }

    /**
     * Complete a purchase from an active reservation
     * 
     * Flow:
     * 1. Validate reservation exists and is active
     * 2. Check reservation hasn't expired
     * 3. Convert reservation to purchase
     * 4. Update inventory (sold units +qty, reserved units -qty)
     * 5. Mark reservation as completed
     * 
     * @throws ReservationExpiredException if reservation timed out
     */
    public CompletePurchaseResponse completePurchase(CompletePurchaseRequest request) {
        log.info("Purchase completion requested: reservationId={}, customerId={}", 
            request.getReservationId(), request.getCustomerId());

        try {
            // Get and validate reservation
            Reservation reservation = reservationRepository.findById(request.getReservationId())
                .orElseThrow(() -> new RuntimeException("Reservation not found: " + request.getReservationId()));

            if (!Reservation.ReservationStatus.ACTIVE.equals(reservation.getStatus())) {
                throw new RuntimeException("Reservation is not active: " + reservation.getStatus());
            }

            if (LocalDateTime.now().isAfter(reservation.getExpiresAt())) {
                throw new ReservationExpiredException("Your reservation has expired. Please start over.");
            }

            // Verify customer ownership of reservation
            if (!reservation.getCustomerId().equals(request.getCustomerId())) {
                throw new IllegalArgumentException("You are not authorized to complete this reservation. This reservation belongs to another customer.");
            }

            // Get inventory and product
            Inventory inventory = inventoryRepository.findById(reservation.getProductId())
                .orElseThrow(() -> new RuntimeException("Inventory not found"));
            
            Product product = productRepository.findById(reservation.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found"));

            // Create purchase record
            double totalPrice = product.getPrice() * reservation.getQuantity();
            
            PurchaseHistory purchase = PurchaseHistory.builder()
                .reservationId(request.getReservationId())
                .productId(reservation.getProductId())
                .customerId(request.getCustomerId())
                .quantity(reservation.getQuantity())
                .totalPrice(totalPrice)
                .status("COMPLETED")
                .purchasedAt(LocalDateTime.now())
                .build();
            
            purchase = purchaseHistoryRepository.save(purchase);
            Long purchaseId = purchase.getPurchaseId();

            // Update inventory (move from reserved to sold)
            inventory.setReservedUnits(inventory.getReservedUnits() - reservation.getQuantity());
            inventory.setSoldUnits(inventory.getSoldUnits() + reservation.getQuantity());
            inventory.setLastUpdated(LocalDateTime.now());
            inventoryRepository.save(inventory);

            // Mark reservation as completed
            reservation.setStatus(Reservation.ReservationStatus.COMPLETED);
            reservation.setCompletedAt(LocalDateTime.now());
            reservationRepository.save(reservation);

            log.info("Purchase completed: id={}, total={}", purchaseId, totalPrice);

            return CompletePurchaseResponse.builder()
                .purchaseId(purchaseId)
                .reservationId(request.getReservationId())
                .productId(reservation.getProductId())
                .quantity(reservation.getQuantity())
                .totalPrice(totalPrice)
                .status("COMPLETED")
                .purchasedAt(purchase.getPurchasedAt().format(DATE_FORMATTER))
                .message("Purchase completed successfully!")
                .build();

        } catch (ReservationExpiredException e) {
            log.warn("Reservation expired: reservationId={}", request.getReservationId());
            throw e;
        } catch (Exception e) {
            log.error("Error during purchase completion", e);
            throw new RuntimeException("Failed to complete purchase: " + e.getMessage(), e);
        }
    }

    /**
     * Get current inventory status for all products
     * Used for the "X left in stock!" banner on product pages
     */
    public List<InventoryStatusResponse> getInventoryStatus() {
        List<Inventory> inventories = inventoryRepository.findAll();
        List<InventoryStatusResponse> responses = new ArrayList<>();

        for (Inventory inv : inventories) {
            Product product = productRepository.findById(inv.getProductId()).orElse(null);
            if (product == null) continue;

            double soldPercentage = inv.getTotalUnits() > 0 
                ? (100.0 * inv.getSoldUnits() / inv.getTotalUnits()) 
                : 0;

            responses.add(InventoryStatusResponse.builder()
                .productId(product.getProductId())
                .sku(product.getSku())
                .modelName(product.getModelName())
                .color(product.getColor())
                .storageGb(product.getStorageGb())
                .totalUnits(inv.getTotalUnits())
                .availableUnits(inv.getAvailableUnits())
                .reservedUnits(inv.getReservedUnits())
                .soldUnits(inv.getSoldUnits())
                .soldPercentage(soldPercentage)
                .build());
        }

        return responses;
    }

    /**
     * Release expired reservations (called periodically by scheduler)
     * Frees up inventory for customers who didn't complete checkout
     */
    public void releaseExpiredReservations() {
        try {
            LocalDateTime now = LocalDateTime.now();
            
            reservationRepository.findAll().stream()
                .filter(r -> Reservation.ReservationStatus.ACTIVE.equals(r.getStatus()) && r.getExpiresAt().isBefore(now))
                .forEach(reservation -> {
                    // Return inventory to available
                    Inventory inventory = inventoryRepository.findById(reservation.getProductId())
                        .orElse(null);
                    if (inventory != null) {
                        inventory.setReservedUnits(inventory.getReservedUnits() - reservation.getQuantity());
                        inventory.setAvailableUnits(inventory.getAvailableUnits() + reservation.getQuantity());
                        inventory.setLastUpdated(LocalDateTime.now());
                        inventoryRepository.save(inventory);
                    }
                    
                    // Mark reservation as expired
                    reservation.setStatus(Reservation.ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);
                    
                    log.info("Expired reservation released: {}", reservation.getReservationId());
                });
            
            log.info("Expired reservations released");
        } catch (Exception e) {
            log.error("Error releasing expired reservations", e);
        }
    }
}
