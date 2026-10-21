package com.mohan.payments.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** The merchant id always comes from the verified token, never from the request body or path. */
public final class CurrentMerchant {

    private CurrentMerchant() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
