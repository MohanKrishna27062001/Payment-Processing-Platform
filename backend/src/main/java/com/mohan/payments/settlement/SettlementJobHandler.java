package com.mohan.payments.settlement;

import com.mohan.payments.outbox.JobType;
import com.mohan.payments.outbox.NonRetryableJobException;
import com.mohan.payments.outbox.OutboxJob;
import com.mohan.payments.outbox.OutboxJobHandler;
import com.mohan.payments.payment.PaymentService;
import com.mohan.payments.payment.domain.Payment;
import com.mohan.payments.payment.domain.PaymentRepository;
import com.mohan.payments.payment.processor.PaymentProcessorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Settles a captured payment: submits it to the processor and, on success, moves it CAPTURED → SETTLED.
 * <p>
 * Idempotent by design. If the payment is already SETTLED (a previous attempt succeeded but the worker died before
 * marking the job done) or was fully REFUNDED before settlement ran, the job completes as SKIPPED instead of
 * failing. A partially refunded payment still settles, for the amount that was not returned.
 * A processor failure throws, which makes the worker retry with backoff and eventually dead-letter the job.
 */
@Component
public class SettlementJobHandler implements OutboxJobHandler {

    static final String WORKER_NAME = "settlement-worker";
    private static final Logger log = LoggerFactory.getLogger(SettlementJobHandler.class);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PaymentProcessorClient processor;

    public SettlementJobHandler(PaymentRepository payments, PaymentService paymentService,
            PaymentProcessorClient processor) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.processor = processor;
    }

    @Override
    public JobType type() {
        return JobType.SETTLE_PAYMENT;
    }

    @Override
    public Outcome handle(OutboxJob job) {
        Payment payment = payments.findById(job.getPaymentId())
                .orElseThrow(() -> new NonRetryableJobException("Payment " + job.getPaymentId() + " not found"));

        return switch (payment.getStatus()) {
            // PARTIALLY_REFUNDED settles too: the merchant is still owed whatever was not returned. Settling
            // the net is what a real scheme does, where refunds offset sales in the same batch. Without this
            // branch a refund taken during the settlement delay would dead-letter the job and strand the
            // remaining money.
            case CAPTURED, PARTIALLY_REFUNDED -> {
                long net = payment.refundableAmount();
                processor.settle(payment.getProcessorReference(), net);
                paymentService.markSettled(payment, WORKER_NAME);
                yield Outcome.COMPLETED;
            }
            case SETTLED, REFUNDED -> {
                log.info("Skipping settlement: payment is already {}", payment.getStatus());
                yield Outcome.SKIPPED;
            }
            default -> throw new NonRetryableJobException(
                    "Payment in state " + payment.getStatus() + " cannot be settled");
        };
    }
}
