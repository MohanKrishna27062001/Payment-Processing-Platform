package com.mohan.payments.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record AppSecurityProperties(Jwt jwt, Cors cors) {

    /**
     * @param secret HMAC key for signing tokens; must be at least 32 bytes. Supplied via JWT_SECRET outside local dev.
     * @param issuer value of the {@code iss} claim; tokens from any other issuer are rejected
     * @param ttl    how long an access token is valid; there is no refresh token, so the user logs in again after this
     */
    public record Jwt(String secret, String issuer, Duration ttl) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
