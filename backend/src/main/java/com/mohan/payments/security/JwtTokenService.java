package com.mohan.payments.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AppSecurityProperties.Jwt properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AppSecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties.jwt();
        this.clock = clock;
    }

    public IssuedToken issue(UUID merchantId, String merchantName) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS); // JWT times are whole seconds
        Instant expiresAt = now.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(merchantId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("name", merchantName)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, properties.ttl().toSeconds());
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
