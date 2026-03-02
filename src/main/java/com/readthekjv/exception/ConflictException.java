package com.readthekjv.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a create operation conflicts with existing data (e.g., duplicate email).
 * Spring MVC's ResponseStatusExceptionResolver maps this to HTTP 409 Conflict.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
