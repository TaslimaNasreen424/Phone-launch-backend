package com.bell.phone.launch.controller;

import com.bell.phone.launch.dto.*;
import com.bell.phone.launch.exception.*;
import com.bell.phone.launch.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class PhoneLaunchController {

    private final ReservationService reservationService;

    /**
     * Reserve a phone
     * POST /api/v1/reserve
     * 
     * Request body:
     * {
     *   "productId": 1,
     *   "customerId": "cust-123",
     *   "quantity": 1,
     *   "sessionToken": "token-xyz",
     *   "deviceFingerprint": "fingerprint-abc"
     * }
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReservePhoneResponse> reservePhone(@Valid @RequestBody ReservePhoneRequest request) {
        try {
            ReservePhoneResponse response = reservationService.reservePhone(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (InsufficientInventoryException e) {
            ReservePhoneResponse errorResponse = ReservePhoneResponse.builder()
                .message(e.getMessage())
                .build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        } catch (InventoryLockedException e) {
            ReservePhoneResponse errorResponse = ReservePhoneResponse.builder()
                .message(e.getMessage())
                .build();
            return ResponseEntity.status(HttpStatus.LOCKED).body(errorResponse);
        } catch (IllegalArgumentException e) {
            ReservePhoneResponse errorResponse = ReservePhoneResponse.builder()
                .message(e.getMessage())
                .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            log.error("Unexpected error during reservation", e);
            ReservePhoneResponse errorResponse = ReservePhoneResponse.builder()
                .message("An unexpected error occurred. Please try again.")
                .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Complete a purchase
     * POST /api/v1/purchase
     * 
     * Request body:
     * {
     *   "reservationId": 1,
     *   "customerId": "cust-123"
     * }
     */
    @PostMapping("/purchase")
    public ResponseEntity<CompletePurchaseResponse> completePurchase(@Valid @RequestBody CompletePurchaseRequest request) {
        try {
            CompletePurchaseResponse response = reservationService.completePurchase(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (ReservationExpiredException e) {
            CompletePurchaseResponse errorResponse = CompletePurchaseResponse.builder()
                .message(e.getMessage())
                .build();
            return ResponseEntity.status(HttpStatus.GONE).body(errorResponse);
        } catch (Exception e) {
            log.error("Unexpected error during purchase completion", e);
            CompletePurchaseResponse errorResponse = CompletePurchaseResponse.builder()
                .message("An unexpected error occurred. Please try again.")
                .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get real-time inventory status
     * GET /api/v1/inventory
     * 
     * Returns current stock levels for all products
     */
    @GetMapping("/inventory")
    public ResponseEntity<List<InventoryStatusResponse>> getInventoryStatus() {
        List<InventoryStatusResponse> inventory = reservationService.getInventoryStatus();
        return ResponseEntity.ok(inventory);
    }

    /**
     * Health check endpoint
     * GET /api/v1/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Service is running");
    }
}
