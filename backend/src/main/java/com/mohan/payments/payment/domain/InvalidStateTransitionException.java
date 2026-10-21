package com.mohan.payments.payment.domain;

import java.util.UUID;

import com.mohan.payments.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends ApiException {

    public InvalidStateTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Invalid state transition",
                "Payment %s cannot move from %s to %s.".formatted(paymentId, from, to));
    }
}
