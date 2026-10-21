package com.mohan.payments.payment.processor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for a real processor, with deterministic "magic" inputs (like Stripe's test cards) so every
 * path can be demonstrated and tested:
 * <table>
 *   <tr><th>Input</th><th>Behaviour</th></tr>
 *   <tr><td>token {@code tok_visa} / {@code tok_success}</td><td>approved, VISA ending 4242</td></tr>
 *   <tr><td>token {@code tok_mastercard}</td><td>approved, MASTERCARD ending 4444</td></tr>
 *   <tr><td>token {@code tok_decline}</td><td>declined: insufficient_funds</td></tr>
 *   <tr><td>token {@code tok_processor_error}</td><td>processor unavailable during authorization</td></tr>
 *   <tr><td>amount ending in .13</td><td>settlement fails twice, then succeeds (shows retry + backoff)</td></tr>
 *   <tr><td>amount ending in .99</td><td>settlement always fails (shows dead-lettering)</td></tr>
 * </table>
 */
@Component
@ConditionalOnProperty(name = "app.processor.mode", havingValue = "stub", matchIfMissing = true)
public class StubPaymentProcessor implements PaymentProcessorClient {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentProcessor.class);
    static final int TRANSIENT_FAILURES_BEFORE_SUCCESS = 2;

    private final Map<String, AtomicInteger> settlementAttempts = new ConcurrentHashMap<>();

    @Override
    public AuthorizationResult authorize(String paymentMethodToken, long amountMinor, String currency) {
        log.debug("Stub authorize amountMinor={} currency={}", amountMinor, currency);
        return switch (paymentMethodToken) {
            case "tok_visa", "tok_success" -> AuthorizationResult.approved(newReference(), "VISA", "4242");
            case "tok_mastercard" -> AuthorizationResult.approved(newReference(), "MASTERCARD", "4444");
            case "tok_decline" -> AuthorizationResult.declined("insufficient_funds");
            case "tok_processor_error" -> throw new ProcessorUnavailableException("simulated: authorization timeout");
            default -> AuthorizationResult.declined("invalid_payment_method");
        };
    }

    @Override
    public void capture(String processorReference, long amountMinor) {
        log.debug("Stub capture processorReference={}", processorReference);
    }

    @Override
    public void refund(String processorReference, long amountMinor) {
        log.debug("Stub refund processorReference={}", processorReference);
    }

    @Override
    public void settle(String processorReference, long amountMinor) {
        long cents = amountMinor % 100;
        if (cents == 99) {
            throw new ProcessorUnavailableException("simulated: settlement service unavailable");
        }
        if (cents == 13) {
            int attempt = settlementAttempts.computeIfAbsent(processorReference, ref -> new AtomicInteger())
                    .incrementAndGet();
            if (attempt <= TRANSIENT_FAILURES_BEFORE_SUCCESS) {
                throw new ProcessorUnavailableException("simulated: settlement timeout (attempt " + attempt + ")");
            }
        }
        settlementAttempts.remove(processorReference);
        log.debug("Stub settle processorReference={}", processorReference);
    }

    private static String newReference() {
        return "proc_" + UUID.randomUUID().toString().replace("-", "");
    }
}
