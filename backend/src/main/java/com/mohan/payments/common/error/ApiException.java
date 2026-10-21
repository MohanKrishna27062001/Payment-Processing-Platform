package com.mohan.payments.common.error;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-facing error. Rendered as an RFC 7807 problem detail by {@link GlobalExceptionHandler}.
 * {@code code} is a stable, machine-readable identifier clients can branch on; the message is for humans.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", detail);
    }

    public static ApiException badRequest(String code, String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, title, detail);
    }

    public static ApiException unauthorized(String code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, "Unauthorized", detail);
    }

    public static ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    public static ApiException unprocessable(String code, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, title, detail);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }
}
