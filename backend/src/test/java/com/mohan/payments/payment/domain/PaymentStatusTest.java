package com.mohan.payments.payment.domain;

import static com.mohan.payments.payment.domain.PaymentStatus.AUTHORIZED;
import static com.mohan.payments.payment.domain.PaymentStatus.CAPTURED;
import static com.mohan.payments.payment.domain.PaymentStatus.FAILED;
import static com.mohan.payments.payment.domain.PaymentStatus.PENDING;
import static com.mohan.payments.payment.domain.PaymentStatus.REFUNDED;
import static com.mohan.payments.payment.domain.PaymentStatus.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks every one of the 36 (from, to) pairs, so any change to the state machine is a deliberate test change. */
class PaymentStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "PENDING>AUTHORIZED", "PENDING>FAILED",
            "AUTHORIZED>CAPTURED", "AUTHORIZED>FAILED",
            "CAPTURED>SETTLED", "CAPTURED>REFUNDED",
            "SETTLED>REFUNDED");

    static Stream<Arguments> allPairs() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void onlyDocumentedTransitionsAreAllowed(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
    }

    @ParameterizedTest
    @MethodSource("terminalStates")
    void failedAndRefundedAreTerminal(PaymentStatus status, boolean terminal) {
        assertThat(status.isTerminal()).isEqualTo(terminal);
    }

    static Stream<Arguments> terminalStates() {
        return Stream.of(Arguments.of(PENDING, false), Arguments.of(AUTHORIZED, false), Arguments.of(CAPTURED, false),
                Arguments.of(SETTLED, false), Arguments.of(FAILED, true), Arguments.of(REFUNDED, true));
    }
}
