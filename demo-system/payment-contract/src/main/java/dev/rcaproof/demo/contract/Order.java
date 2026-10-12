package dev.rcaproof.demo.contract;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record Order(@NotBlank String orderId, @NotBlank String customerId,
                    @NotNull @DecimalMin("0.01") @Digits(integer=10, fraction=2) BigDecimal amount) {}
