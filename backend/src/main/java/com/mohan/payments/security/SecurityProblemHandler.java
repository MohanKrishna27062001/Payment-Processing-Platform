package com.mohan.payments.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security failures happen in the filter chain, before Spring MVC. Delegating to the MVC exception resolver lets
 * {@link com.mohan.payments.common.error.GlobalExceptionHandler} render them in the same problem-detail format as
 * every other error.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public SecurityProblemHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        unauthorized(request, response, ex, "Bearer");
    }

    /** The same problem-detail 401, announcing a different scheme (e.g. Basic for the operator endpoints). */
    AuthenticationEntryPoint challenging(String challenge) {
        return (request, response, ex) -> unauthorized(request, response, ex, challenge);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex,
            String challenge) throws IOException, ServletException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        resolver.resolveException(request, response, null, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        resolver.resolveException(request, response, null, ex);
    }
}
