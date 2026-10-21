package com.mohan.payments.payment.processor;

/**
 * The boundary to the external payment processor (Stripe, Adyen, a card network...). The rest of the system only
 * depends on this interface; going live means adding a real implementation, nothing else changes.
 * <p>
 * Implementations must be idempotent per {@code processorReference}, as real processors are, so a retried call
 * never moves money twice. Transient failures are signalled with {@link ProcessorUnavailableException}.
 */
public interface PaymentProcessorClient {

    /** Ask the issuer to reserve funds. A decline is a normal result, not an exception. */
    AuthorizationResult authorize(String paymentMethodToken, long amountMinor, String currency);

    void capture(String processorReference, long amountMinor);

    void refund(String processorReference, long amountMinor);

    /** Submit a captured payment for settlement (funds move to the merchant). */
    void settle(String processorReference, long amountMinor);
}
