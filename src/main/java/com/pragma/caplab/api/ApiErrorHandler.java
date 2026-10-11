package com.pragma.caplab.api;

import com.pragma.caplab.core.ClusterService;
import com.pragma.caplab.domain.LabException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiErrorHandler {

    private final ClusterService cluster;

    public ApiErrorHandler(ClusterService cluster) {
        this.cluster = cluster;
    }

    @ExceptionHandler(LabException.class)
    ResponseEntity<ApiError> lab(LabException e) {
        return ResponseEntity.status(e.status())
                .body(new ApiError(e.code(), e.getMessage(), e.status(), cluster.mode(), e.details()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ApiError> validation(WebExchangeBindException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(
                "VALIDATION_ERROR", "La petición no es válida.", 400, cluster.mode(), fields));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ApiError> input(ServerWebInputException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(
                "BAD_REQUEST", "El cuerpo o los parámetros de la petición son inválidos.", 400, cluster.mode(), Map.of()));
    }
}
