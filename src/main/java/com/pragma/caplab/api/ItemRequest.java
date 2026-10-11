package com.pragma.caplab.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ItemRequest(
        @Size(max = 40) String id,
        @NotBlank @Size(max = 60) String name,
        @NotNull @Min(0) Integer quantity
) {
}
