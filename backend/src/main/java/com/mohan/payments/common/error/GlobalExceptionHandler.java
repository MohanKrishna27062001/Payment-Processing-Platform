package com.mohan.payments.common.error;

import java.util.List;
import java.util.Map;

import com.mohan.payments.payment.processor.ProcessorRejectedException;
import com.mohan.payments.payment.processor.ProcessorUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into the same RFC 7807 shape:
 * {@code {type, title, status, detail, code, correlationId}}.
 * Request values are never echoed back, so card data or secrets cannot leak through error messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        log.atInfo().addKeyValue("errorCode", ex.getCode()).addKeyValue("httpStatus", ex.getStatus().value())
                .log("Request rejected: {}", ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(Problems.of(ex.getStatus(), ex.getCode(), ex.getTitle(), ex.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Concurrent modification detected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION", "Concurrent modification",
                "The payment was modified by another request. Fetch it again and retry if still applicable."));
    }

    @ExceptionHandler(ProcessorUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleProcessorUnavailable(ProcessorUnavailableException ex) {
        log.warn("Payment processor unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "PROCESSOR_UNAVAILABLE",
                        "Payment processor unavailable",
                        "The payment processor did not respond. Retry with the same Idempotency-Key."));
    }

    @ExceptionHandler(ProcessorRejectedException.class)
    public ResponseEntity<ProblemDetail> handleProcessorRejected(ProcessorRejectedException ex) {
        // The processor understood us and refused permanently, so retrying the same request will not help.
        log.warn("Payment processor rejected the request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "PROCESSOR_REJECTED",
                        "Payment processor rejected the request",
                        "The payment processor refused this operation (" + ex.getCode()
                                + "). Retrying will not help."));
    }

    /** Backstop for constraint violations raised outside Spring MVC's own parameter validation. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                "One or more parameters are invalid.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        boolean badToken = ex instanceof InvalidBearerTokenException;
        String code = badToken ? "INVALID_TOKEN" : "UNAUTHENTICATED";
        String detail = badToken
                ? "The access token is invalid or has expired. Log in again to obtain a new one."
                : "Authentication is required. Send 'Authorization: Bearer <token>'.";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Problems.of(HttpStatus.UNAUTHORIZED, code, "Unauthorized", detail));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Forbidden", "You do not have access to this resource."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error",
                "An unexpected error occurred. Quote the correlationId when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                "One or more fields are invalid.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /** Adds code and correlationId to the problem details Spring MVC builds for framework exceptions. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.getBody();
        }
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            Problems.enrich(problem, codeFor(ex, statusCode));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String codeFor(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MissingRequestHeaderException missing
                && "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName())) {
            return "IDEMPOTENCY_KEY_REQUIRED";
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "ERROR";
    }
}
