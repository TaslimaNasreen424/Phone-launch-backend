package com.bell.phone.launch.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompletePurchaseRequest {
    @NotNull(message = "Reservation ID is required")
    @Positive(message = "Reservation ID must be positive")
    private Long reservationId;

    @NotBlank(message = "Customer ID is required")
    private String customerId;
}
