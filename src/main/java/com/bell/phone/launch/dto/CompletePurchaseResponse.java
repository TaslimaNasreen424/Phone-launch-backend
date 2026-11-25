package com.bell.phone.launch.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompletePurchaseResponse {
    private Long purchaseId;
    private Long reservationId;
    private Long productId;
    private Integer quantity;
    private Double totalPrice;
    private String status;
    private String purchasedAt;
    private String message;
}
