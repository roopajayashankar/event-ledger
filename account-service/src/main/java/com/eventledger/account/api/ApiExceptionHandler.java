package com.eventledger.account.api;

import com.eventledger.account.service.AccountNotFoundException;
import com.eventledger.account.service.CurrencyMismatchException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 {@link ProblemDetail} bodies (native in Spring
 * Boot 3) so every error response is consistent and machine-readable.
 * Extending {@link ResponseEntityExceptionHandler} means framework errors
 * (malformed JSON, unknown enum value, etc.) already return ProblemDetail; we
 * add field-level detail for validation and mappings for domain exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Currency mismatch");
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setTitle("Invalid request");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", String.valueOf(fieldError.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
