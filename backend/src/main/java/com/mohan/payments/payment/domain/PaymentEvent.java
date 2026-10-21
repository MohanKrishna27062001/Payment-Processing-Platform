package com.mohan.payments.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** One row of the append-only audit trail: who/what moved a payment from which state to which, and when. */
@Entity
@Immutable
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private Actor.ActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    private String reason;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentEvent() {
    }

    PaymentEvent(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus, Actor actor, String reason,
            String correlationId, Instant createdAt) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.reason = reason;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public Actor.ActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
