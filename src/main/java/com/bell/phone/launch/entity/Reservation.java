package com.bell.phone.launch.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "RESERVATIONS")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RESERVATION_ID")
    private Long reservationId;

    @Column(name = "PRODUCT_ID", nullable = false)
    private Long productId;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private String customerId;

    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity;

    @Column(name = "STATUS")
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "RESERVED_AT")
    private LocalDateTime reservedAt;

    @Column(name = "EXPIRES_AT")
    private LocalDateTime expiresAt;

    @Column(name = "COMPLETED_AT")
    private LocalDateTime completedAt;

    @Column(name = "SESSION_TOKEN")
    private String sessionToken;

    @Column(name = "DEVICE_FINGERPRINT")
    private String deviceFingerprint;

    public enum ReservationStatus {
        ACTIVE, COMPLETED, EXPIRED, CANCELLED
    }
}
