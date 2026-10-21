package com.mohan.payments.outbox;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Shows the async backlog in {@code /actuator/health}. A growing backlog reports DEGRADED (HTTP 200) rather than DOWN:
 * the API can still serve traffic, and failing readiness would only take healthy instances out of the load balancer.
 * It is deliberately not part of the liveness or readiness groups.
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "Outbox processing is lagging");

    private final OutboxMetrics metrics;
    private final OutboxProperties properties;

    public OutboxHealthIndicator(OutboxMetrics metrics, OutboxProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public Health health() {
        OutboxMetrics.Snapshot s = metrics.snapshot();
        boolean lagging = s.lagSeconds() > properties.lagWarning().toSeconds();
        return Health.status(lagging ? DEGRADED : Status.UP)
                .withDetail("pending", s.pending())
                .withDetail("processing", s.processing())
                .withDetail("dead", s.dead())
                .withDetail("lagSeconds", s.lagSeconds())
                .build();
    }
}
