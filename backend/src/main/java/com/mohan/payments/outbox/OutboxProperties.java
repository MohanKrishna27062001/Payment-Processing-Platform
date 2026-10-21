package com.mohan.payments.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param settlementDelay how long after capture a payment becomes eligible for settlement (simulates batch cut-off)
 * @param batchSize       jobs claimed per poll
 * @param maxAttempts     attempts before a job is dead-lettered
 * @param initialBackoff  delay before the first retry; doubles on each further attempt
 * @param maxBackoff      upper bound for the retry delay
 * @param stuckAfter      a PROCESSING job older than this is assumed orphaned by a crashed worker and re-queued
 * @param lagWarning      processing lag above which the outbox health check reports DEGRADED
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        Duration settlementDelay,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration stuckAfter,
        Duration lagWarning) {
}
