package com.bell.phone.launch.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservePhoneRequest {
    @NotNull(message = "Product ID is required")
    @Positive(message = "Product ID must be positive")
    private Long productId;

    @NotBlank(message = "Customer ID is required")
    private String customerId;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    @Max(value = 5, message = "Cannot reserve more than 5 units per customer")
    private Integer quantity;

    @NotBlank(message = "Session token is required")
    private String sessionToken;

    @NotBlank(message = "Device fingerprint is required")
    private String deviceFingerprint;
}
