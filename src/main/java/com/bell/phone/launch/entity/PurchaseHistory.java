package com.bell.phone.launch.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "PURCHASE_HISTORY")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PURCHASE_ID")
    private Long purchaseId;

    @Column(name = "RESERVATION_ID")
    private Long reservationId;

    @Column(name = "PRODUCT_ID", nullable = false)
    private Long productId;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private String customerId;

    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity;

    @Column(name = "UNIT_PRICE", nullable = false)
    private Double unitPrice;

    @Column(name = "TOTAL_PRICE", nullable = false)
    private Double totalPrice;

    @Column(name = "STATUS")
    private String status;

    @Column(name = "PURCHASED_AT")
    private LocalDateTime purchasedAt;
}
