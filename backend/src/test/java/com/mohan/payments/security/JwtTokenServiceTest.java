package com.mohan.payments.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtTokenServiceTest {

    private static final AppSecurityProperties PROPERTIES = new AppSecurityProperties(
            new AppSecurityProperties.Jwt("unit-test-secret-that-is-at-least-32-bytes", "payments-api",
                    Duration.ofMinutes(15)),
            new AppSecurityProperties.Cors(List.of()));

    private final SecurityConfig config = new SecurityConfig();
    private final SecretKey key = config.jwtSigningKey(PROPERTIES);
    private final JwtDecoder decoder = config.jwtDecoder(key, PROPERTIES);

    @Test
    void issuedTokenCarriesMerchantIdAndExpiry() {
        UUID merchantId = UUID.randomUUID();
        JwtTokenService service = new JwtTokenService(config.jwtEncoder(key), PROPERTIES, Clock.systemUTC());

        JwtTokenService.IssuedToken token = service.issue(merchantId, "Acme");
        Jwt decoded = decoder.decode(token.value());

        assertThat(decoded.getSubject()).isEqualTo(merchantId.toString());
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("payments-api");
        assertThat(decoded.getExpiresAt()).isEqualTo(token.expiresAt());
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void expiredTokenIsRejected() {
        Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        JwtTokenService service = new JwtTokenService(config.jwtEncoder(key), PROPERTIES, anHourAgo);

        String token = service.issue(UUID.randomUUID(), "Acme").value();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        AppSecurityProperties other = new AppSecurityProperties(
                new AppSecurityProperties.Jwt("a-completely-different-secret-of-32-bytes", "payments-api",
                        Duration.ofMinutes(15)),
                PROPERTIES.cors());
        String forged = new JwtTokenService(config.jwtEncoder(config.jwtSigningKey(other)), other, Clock.systemUTC())
                .issue(UUID.randomUUID(), "Mallory").value();

        assertThatThrownBy(() -> decoder.decode(forged)).hasMessageContaining("signature");
    }

    @Test
    void shortSecretIsRefusedAtStartup() {
        AppSecurityProperties weak = new AppSecurityProperties(
                new AppSecurityProperties.Jwt("too-short", "payments-api", Duration.ofMinutes(15)), PROPERTIES.cors());

        assertThatThrownBy(() -> config.jwtSigningKey(weak)).isInstanceOf(IllegalStateException.class);
    }
}
