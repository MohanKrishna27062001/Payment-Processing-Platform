package com.mohan.payments.payment.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mohan.payments.idempotency.IdempotencyService;
import com.mohan.payments.payment.PaymentSearchCriteria;
import com.mohan.payments.payment.PaymentSort;
import com.mohan.payments.payment.PaymentService;
import com.mohan.payments.payment.PaymentService.CreatePaymentCommand;
import com.mohan.payments.payment.PaymentService.PaymentDetails;
import com.mohan.payments.payment.api.PaymentDtos.CreatePaymentRequest;
import com.mohan.payments.payment.api.PaymentDtos.PageResponse;
import com.mohan.payments.payment.api.PaymentDtos.PaymentDetailResponse;
import com.mohan.payments.payment.api.PaymentDtos.PaymentEventResponse;
import com.mohan.payments.payment.api.PaymentDtos.PaymentResponse;
import com.mohan.payments.payment.api.PaymentDtos.RefundRequest;
import com.mohan.payments.payment.domain.Payment;
import com.mohan.payments.payment.domain.PaymentStatus;
import com.mohan.payments.security.CurrentMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant-facing payment API. All money-moving operations (create, capture, refund) require an
 * {@code Idempotency-Key} header so that client retries can never double-charge or double-refund.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        UUID merchantId = CurrentMerchant.id(jwt);
        return idempotency.execute(merchantId, idempotencyKey, "POST /api/v1/payments", request, () -> {
            Payment payment = payments.create(merchantId, new CreatePaymentCommand(request.amount(),
                    request.currency(), request.merchantReference(), request.paymentMethod().token()));
            return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.getId()))
                    .body(PaymentResponse.from(payment));
        });
    }

    @GetMapping("/{paymentId}")
    public PaymentDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        PaymentDetails details = payments.get(CurrentMerchant.id(jwt), paymentId);
        return new PaymentDetailResponse(PaymentResponse.from(details.payment()),
                details.events().stream().map(PaymentEventResponse::from).toList());
    }

    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<?> capture(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        UUID merchantId = CurrentMerchant.id(jwt);
        return idempotency.execute(merchantId, idempotencyKey, "POST /api/v1/payments/" + paymentId + "/capture",
                null, () -> ResponseEntity.ok(PaymentResponse.from(payments.capture(merchantId, paymentId))));
    }

    /**
     * Refunds all or part of a payment. The body is optional: {@code {"amount": 500}} returns 5.00, and no
     * body at all returns everything still refundable.
     * <p>
     * The amount is part of the idempotency fingerprint, so two different instalments need two different keys,
     * while a retry of the <i>same</i> instalment replays rather than refunding twice.
     */
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<?> refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody(required = false) @Valid RefundRequest request) {
        UUID merchantId = CurrentMerchant.id(jwt);
        Long amount = request == null ? null : request.amount();
        return idempotency.execute(merchantId, idempotencyKey, "POST /api/v1/payments/" + paymentId + "/refund",
                request, () -> ResponseEntity.ok(
                        PaymentResponse.from(payments.refund(merchantId, paymentId, amount))));
    }

    /**
     * Lists the merchant's payments, newest first. Example:
     * {@code GET /api/v1/payments?status=CAPTURED&from=2026-10-01T00:00:00Z&q=order-1&sort=amount,desc&page=0}
     */
    @GetMapping
    public PageResponse<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false) List<PaymentStatus> statuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 3) String currency,
            @RequestParam(required = false) @Min(0) Long minAmount,
            @RequestParam(required = false) @Min(0) Long maxAmount,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = PaymentSort.DEFAULT) @Size(max = 40) String sort) {
        PaymentSearchCriteria criteria = new PaymentSearchCriteria(statuses, from, to, q, currency, minAmount,
                maxAmount);
        return PageResponse.from(payments.search(CurrentMerchant.id(jwt), criteria, page, size,
                PaymentSort.parse(sort)).map(PaymentResponse::from));
    }
}
