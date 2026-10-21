package com.mohan.payments.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mohan.payments.common.error.ApiException;
import com.mohan.payments.common.web.CorrelationIdFilter;
import com.mohan.payments.outbox.OutboxService;
import com.mohan.payments.payment.domain.Actor;
import com.mohan.payments.payment.domain.InvalidStateTransitionException;
import com.mohan.payments.payment.domain.Payment;
import com.mohan.payments.payment.domain.PaymentEvent;
import com.mohan.payments.payment.domain.PaymentEventRepository;
import com.mohan.payments.payment.domain.PaymentRepository;
import com.mohan.payments.payment.domain.PaymentStatus;
import com.mohan.payments.payment.processor.AuthorizationResult;
import com.mohan.payments.payment.processor.PaymentProcessorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment use cases. Every state change goes through {@link #record}, which writes the audit event, updates
 * metrics and logs, all in the same transaction as the state change itself.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Actor PROCESSOR = Actor.system("payment-processor");
    // 12-19 digits, optionally separated by spaces or dashes: looks like a card number (PAN).
    private static final Pattern PAN_LIKE = Pattern.compile("^[0-9][0-9 -]{10,22}[0-9]$");

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentProcessorClient processor;
    private final OutboxService outbox;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, PaymentProcessorClient processor,
            OutboxService outbox, PaymentMetrics metrics, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.processor = processor;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public Payment create(UUID merchantId, CreatePaymentCommand command) {
        rejectRawCardData(command.paymentMethodToken());
        Instant now = clock.instant();
        Payment payment = payments.save(Payment.create(merchantId, command.amountMinor(), command.currency(),
                command.merchantReference(), now));
        MDC.put(CorrelationIdFilter.PAYMENT_ID_MDC_KEY, payment.getId().toString());
        record(payment, payment.creationEvent(Actor.merchant(merchantId), correlationId()));

        // A processor outage throws and rolls the whole transaction back, so the client can retry
        // with the same Idempotency-Key. A decline is a business outcome and is stored as FAILED.
        AuthorizationResult result = processor.authorize(command.paymentMethodToken(), command.amountMinor(),
                command.currency());
        PaymentEvent outcome = result.approved()
                ? payment.authorize(result.processorReference(), result.cardBrand(), result.cardLast4(), PROCESSOR,
                        correlationId(), clock.instant())
                : payment.fail(result.declineReason(), PROCESSOR, correlationId(), clock.instant());
        record(payment, outcome);
        return payment;
    }

    @Transactional(readOnly = true)
    public PaymentDetails get(UUID merchantId, UUID paymentId) {
        Payment payment = load(merchantId, paymentId);
        return new PaymentDetails(payment, events.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId));
    }

    @Transactional
    public Payment capture(UUID merchantId, UUID paymentId) {
        Payment payment = loadForUpdate(merchantId, paymentId);
        requireStatus(payment, PaymentStatus.CAPTURED);
        processor.capture(payment.getProcessorReference(), payment.getAmountMinor());
        record(payment, payment.transitionTo(PaymentStatus.CAPTURED, Actor.merchant(merchantId),
                "captured_by_merchant", correlationId(), clock.instant()));
        // Written in the same transaction as the capture: either both happen or neither does.
        outbox.enqueueSettlement(payment.getId());
        return payment;
    }

    /**
     * Refunds {@code amountMinor}, or everything still refundable when it is null.
     * <p>
     * Both checks run before the processor is called: a payment that cannot be refunded at all, and an amount
     * larger than what is left. Asking the processor first and rejecting afterwards would risk money leaving
     * while this transaction rolls back. The row lock makes those checks hold until commit: a concurrent refund
     * waits here and then validates against the amount this one has already taken.
     */
    @Transactional
    public Payment refund(UUID merchantId, UUID paymentId, Long amountMinor) {
        Payment payment = loadForUpdate(merchantId, paymentId);
        PaymentStatus next = payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                ? PaymentStatus.PARTIALLY_REFUNDED
                : PaymentStatus.REFUNDED;
        requireStatus(payment, next);

        long amount = amountMinor == null ? payment.refundableAmount() : amountMinor;
        if (amount <= 0 || amount > payment.refundableAmount()) {
            throw ApiException.unprocessable("REFUND_AMOUNT_INVALID", "Refund amount is not valid",
                    "Refund must be between 1 and " + payment.refundableAmount()
                            + " minor units; " + payment.getRefundedAmountMinor()
                            + " of " + payment.getAmountMinor() + " has already been refunded.");
        }

        processor.refund(payment.getProcessorReference(), amount);
        record(payment, payment.refund(amount, Actor.merchant(merchantId), correlationId(), clock.instant()));
        return payment;
    }

    /** Called by the settlement worker once the processor has confirmed settlement. */
    @Transactional
    public void markSettled(Payment payment, String component) {
        record(payment, payment.transitionTo(PaymentStatus.SETTLED, Actor.system(component), "settled",
                correlationId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public Page<Payment> search(UUID merchantId, PaymentSearchCriteria criteria, int page, int size, Sort sort) {
        PageRequest pageable = PageRequest.of(page, size, sort);
        return payments.findAll(PaymentSpecifications.matching(merchantId, criteria), pageable);
    }

    private Payment load(UUID merchantId, UUID paymentId) {
        return found(payments.findByIdAndMerchantId(paymentId, merchantId), paymentId);
    }

    /**
     * For operations that call the processor: locks the payment row until commit, so two requests for the same
     * payment run one after the other instead of both calling out on the same stale state. Requests for different
     * payments never wait on each other.
     */
    private Payment loadForUpdate(UUID merchantId, UUID paymentId) {
        return found(payments.findForUpdate(paymentId, merchantId), paymentId);
    }

    private static Payment found(Optional<Payment> payment, UUID paymentId) {
        Payment result = payment
                .orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " was not found."));
        MDC.put(CorrelationIdFilter.PAYMENT_ID_MDC_KEY, paymentId.toString());
        return result;
    }

    /** Fails before calling the processor when the transition is impossible anyway. */
    private static void requireStatus(Payment payment, PaymentStatus next) {
        if (!payment.getStatus().canTransitionTo(next)) {
            throw new InvalidStateTransitionException(payment.getId(), payment.getStatus(), next);
        }
    }

    private void record(Payment payment, PaymentEvent event) {
        events.save(event);
        metrics.recordTransition(event.getFromStatus(), event.getToStatus());
        log.atInfo()
                .addKeyValue("fromStatus", event.getFromStatus())
                .addKeyValue("toStatus", event.getToStatus())
                .addKeyValue("actor", event.getActorType() + ":" + event.getActorId())
                .addKeyValue("reason", event.getReason())
                .log("Payment {} {} -> {}", payment.getId(), event.getFromStatus(), event.getToStatus());
    }

    private static void rejectRawCardData(String token) {
        if (PAN_LIKE.matcher(token).matches()) {
            // Never log or echo the value itself.
            throw ApiException.badRequest("RAW_CARD_DATA_REJECTED", "Raw card data rejected",
                    "Raw card numbers are not accepted. Tokenize the card with the processor and send the token.");
        }
    }

    private static String correlationId() {
        return CorrelationIdFilter.current();
    }

    public record CreatePaymentCommand(long amountMinor, String currency, String merchantReference,
            String paymentMethodToken) {

        @Override
        public String toString() {
            return "CreatePaymentCommand[amountMinor=" + amountMinor + ", currency=" + currency
                    + ", merchantReference=" + merchantReference + ", paymentMethodToken=****]";
        }
    }

    public record PaymentDetails(Payment payment, List<PaymentEvent> events) {
    }
}
