package com.bell.phone.launch.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "PRODUCTS")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    @Column(name = "PRODUCT_ID")
    private Long productId;

    @Column(name = "MODEL_NAME", nullable = false)
    private String modelName;

    @Column(name = "COLOR", nullable = false)
    private String color;

    @Column(name = "STORAGE_GB", nullable = false)
    private Integer storageGb;

    @Column(name = "PRICE", nullable = false)
    private Double price;

    @Column(name = "SKU", nullable = false, unique = true)
    private String sku;

    @Column(name = "LAUNCH_DATE")
    private LocalDateTime launchDate;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;
}
