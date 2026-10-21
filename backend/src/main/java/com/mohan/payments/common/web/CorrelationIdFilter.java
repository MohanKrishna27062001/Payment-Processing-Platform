package com.mohan.payments.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID: taken from {@code X-Request-Id} when the caller sends a well-formed one,
 * generated otherwise. It is put in the logging MDC (so every log line carries it), echoed in the response, and
 * copied onto async jobs so the background work for a request can be found with the same ID.
 * Also writes one structured access-log line per API request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    /** Set by services once a request is about a specific payment; cleared here at the end of the request. */
    public static final String PAYMENT_ID_MDC_KEY = "paymentId";

    // Restricting the charset prevents log injection through a caller-supplied header.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Logger accessLog = LoggerFactory.getLogger("http.access");

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")) {
                accessLog.atInfo()
                        .addKeyValue("http.method", request.getMethod())
                        .addKeyValue("http.path", request.getRequestURI())
                        .addKeyValue("http.status", response.getStatus())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            }
            MDC.remove(MDC_KEY);
            MDC.remove(PAYMENT_ID_MDC_KEY);
        }
    }
}
