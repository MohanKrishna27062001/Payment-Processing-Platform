package com.mohan.payments.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator account for the protected actuator endpoints ({@code /actuator/outbox}, {@code /actuator/metrics}).
 * It is a separate identity from merchants on purpose: a merchant token says "I may see my own payments", which is
 * not the same as "I may see every merchant's failed jobs and requeue them".
 *
 * @param username operator login, sent with HTTP Basic
 * @param password operator password; when blank (the default) no operator account exists and the protected
 *                 endpoints refuse everyone. Supplied via OPS_PASSWORD from a secret store outside local dev.
 */
@ConfigurationProperties("app.security.operator")
public record OperatorProperties(String username, String password) {

    public boolean enabled() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
