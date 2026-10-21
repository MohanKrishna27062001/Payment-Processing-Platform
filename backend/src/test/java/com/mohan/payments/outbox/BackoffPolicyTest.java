package com.mohan.payments.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private static final Duration INITIAL = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofSeconds(30);

    @Test
    void delayDoublesPerAttemptUpToTheCap() {
        BackoffPolicy noJitter = new BackoffPolicy(INITIAL, MAX, () -> 1.0);

        assertThat(noJitter.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(noJitter.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(noJitter.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(noJitter.delayAfter(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(noJitter.delayAfter(5)).isEqualTo(MAX);
        assertThat(noJitter.delayAfter(50)).isEqualTo(MAX);
    }

    @Test
    void jitterKeepsDelayBetweenHalfAndFullValue() {
        assertThat(new BackoffPolicy(INITIAL, MAX, () -> 0.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(new BackoffPolicy(INITIAL, MAX, () -> 0.5).delayAfter(3)).isEqualTo(Duration.ofSeconds(6));
    }
}
