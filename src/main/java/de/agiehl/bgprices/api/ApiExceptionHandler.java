package de.agiehl.bgprices.api;

import java.time.Instant;
import org.springframework.http.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record InvalidRequest(String status, String errorCode, String message, Instant timestamp) { }

    @ExceptionHandler({MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<InvalidRequest> invalid(Exception exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new InvalidRequest("ERROR", "INVALID_REQUEST", "name must contain 1–300 characters; bggId must be a positive integer", Instant.now()));
    }
}
