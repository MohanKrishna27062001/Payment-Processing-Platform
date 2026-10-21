package com.mohan.payments.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exponential backoff with jitter: attempt n waits about {@code initial * 2^(n-1)}, capped at {@code max}, with the
 * delay randomised between 50% and 100%. Jitter stops many failed jobs from retrying in lockstep and hammering a
 * recovering downstream at the same instant.
 */
@Component
public class BackoffPolicy {

    private final Duration initial;
    private final Duration max;
    private final DoubleSupplier random;

    @Autowired
    public BackoffPolicy(OutboxProperties properties) {
        this(properties.initialBackoff(), properties.maxBackoff(), () -> ThreadLocalRandom.current().nextDouble());
    }

    BackoffPolicy(Duration initial, Duration max, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /** @param attempt the attempt that just failed, starting at 1 */
    public Duration delayAfter(int attempt) {
        long base = initial.toMillis() * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(base, max.toMillis());
        long jittered = capped / 2 + Math.round(capped / 2.0 * random.getAsDouble());
        return Duration.ofMillis(jittered);
    }
}
