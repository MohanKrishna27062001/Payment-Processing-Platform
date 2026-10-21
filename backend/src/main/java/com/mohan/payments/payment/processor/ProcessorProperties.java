package com.mohan.payments.payment.processor;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the outbound processor calls.
 *
 * @param mode           {@code stub} for the in-process {@link StubPaymentProcessor} (default), {@code http} for
 *                       {@link HttpPaymentProcessorClient} talking to a real endpoint or a WireMock stub
 * @param baseUrl        root URL of the processor
 * @param connectTimeout how long to wait for the TCP connection
 * @param readTimeout    how long to wait for the response. Keep this low: the call happens inside a database
 *                       transaction, so a slow processor holds a pooled connection for exactly this long.
 */
@ConfigurationProperties("app.processor")
public record ProcessorProperties(String mode, String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
