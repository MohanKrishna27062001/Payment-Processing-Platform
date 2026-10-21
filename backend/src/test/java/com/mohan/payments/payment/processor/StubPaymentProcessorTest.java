package com.mohan.payments.payment.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StubPaymentProcessorTest {

    private final StubPaymentProcessor processor = new StubPaymentProcessor();

    @Test
    void testTokensDriveAuthorizationOutcome() {
        AuthorizationResult visa = processor.authorize("tok_visa", 1000, "USD");
        assertThat(visa.approved()).isTrue();
        assertThat(visa.cardBrand()).isEqualTo("VISA");
        assertThat(visa.cardLast4()).isEqualTo("4242");
        assertThat(visa.processorReference()).startsWith("proc_");

        assertThat(processor.authorize("tok_decline", 1000, "USD").declineReason()).isEqualTo("insufficient_funds");
        assertThat(processor.authorize("tok_unknown", 1000, "USD").declineReason())
                .isEqualTo("invalid_payment_method");
        assertThatThrownBy(() -> processor.authorize("tok_processor_error", 1000, "USD"))
                .isInstanceOf(ProcessorUnavailableException.class);
    }

    @Test
    void amountEndingIn13FailsSettlementTwiceThenSucceeds() {
        assertThatThrownBy(() -> processor.settle("proc_a", 1013)).isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.settle("proc_a", 1013)).isInstanceOf(ProcessorUnavailableException.class);
        assertThatCode(() -> processor.settle("proc_a", 1013)).doesNotThrowAnyException();
    }

    @Test
    void amountEndingIn99AlwaysFailsSettlement() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> processor.settle("proc_b", 1099))
                    .isInstanceOf(ProcessorUnavailableException.class);
        }
    }

    @Test
    void ordinaryAmountsSettleFirstTime() {
        assertThatCode(() -> processor.settle("proc_c", 1000)).doesNotThrowAnyException();
    }
}
