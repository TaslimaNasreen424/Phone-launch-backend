package com.bell.phone.launch.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "INVENTORY")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {
    @Id
    @Column(name = "INVENTORY_ID")
    private Long inventoryId;

    @Column(name = "PRODUCT_ID", nullable = false)
    private Long productId;

    @Column(name = "TOTAL_UNITS", nullable = false)
    private Long totalUnits;

    @Column(name = "AVAILABLE_UNITS", nullable = false)
    private Long availableUnits;

    @Column(name = "RESERVED_UNITS", nullable = false)
    private Long reservedUnits;

    @Column(name = "SOLD_UNITS", nullable = false)
    private Long soldUnits;

    @Column(name = "LAST_UPDATED")
    private LocalDateTime lastUpdated;

    @Version
    @Column(name = "VERSION")
    private Long optimisticLockVersion;
}
