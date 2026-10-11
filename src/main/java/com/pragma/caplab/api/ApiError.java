package com.pragma.caplab.api;

import com.pragma.caplab.domain.Mode;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        int status,
        Mode mode,
        Map<String, Object> details
) {
}
