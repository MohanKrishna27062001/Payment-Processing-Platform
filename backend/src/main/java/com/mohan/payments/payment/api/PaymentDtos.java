package com.mohan.payments.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mohan.payments.payment.domain.Payment;
import com.mohan.payments.payment.domain.PaymentEvent;
import com.mohan.payments.payment.domain.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * @param amount amount in minor units (e.g. cents): 1050 = 10.50
     */
    public record CreatePaymentRequest(
            @NotNull @Positive @Max(100_000_000) Long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as USD") String currency,
            @Size(max = 100) String merchantReference,
            @NotNull @Valid PaymentMethod paymentMethod) {
    }

    /** A processor token representing the card. Raw card numbers are rejected. */
    public record PaymentMethod(@NotBlank @Size(max = 100) String token) {

        @Override
        public String toString() {
            return "PaymentMethod[token=****]";
        }
    }

    public record CardSummary(String brand, String last4) {
    }

    /**
     * @param amount how much to return, in minor units. Omit the body entirely, or leave this null, to refund
     *               everything still refundable -- which is what a client written before partial refunds did.
     */
    public record RefundRequest(@Positive @Max(100_000_000) Long amount) {
    }

    public record PaymentResponse(
            UUID id,
            PaymentStatus status,
            long amount,
            String currency,
            String merchantReference,
            CardSummary card,
            String processorReference,
            String failureReason,
            long refundedAmount,
            long refundableAmount,
            Instant createdAt,
            Instant updatedAt) {

        public static PaymentResponse from(Payment p) {
            CardSummary card = p.getCardBrand() == null ? null : new CardSummary(p.getCardBrand(), p.getCardLast4());
            return new PaymentResponse(p.getId(), p.getStatus(), p.getAmountMinor(), p.getCurrency(),
                    p.getMerchantReference(), card, p.getProcessorReference(), p.getFailureReason(),
                    p.getRefundedAmountMinor(), p.refundableAmount(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record PaymentEventResponse(
            PaymentStatus fromStatus,
            PaymentStatus toStatus,
            String actorType,
            String actorId,
            String reason,
            String correlationId,
            Instant occurredAt) {

        static PaymentEventResponse from(PaymentEvent e) {
            return new PaymentEventResponse(e.getFromStatus(), e.getToStatus(), e.getActorType().name(),
                    e.getActorId(), e.getReason(), e.getCorrelationId(), e.getCreatedAt());
        }
    }

    public record PaymentDetailResponse(PaymentResponse payment, List<PaymentEventResponse> events) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
