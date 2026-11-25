package com.bell.phone.launch.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryStatusResponse {
    private Long productId;
    private String sku;
    private String modelName;
    private String color;
    private Integer storageGb;
    private Long totalUnits;
    private Long availableUnits;
    private Long reservedUnits;
    private Long soldUnits;
    private Double soldPercentage;
}
