package com.bell.phone.launch.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservePhoneResponse {
    private Long reservationId;
    private Long productId;
    private Integer quantity;
    private String status;
    private String expiresAt;
    private String message;
}
