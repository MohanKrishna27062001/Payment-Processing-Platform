package com.mohan.payments.idempotency;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    static IdempotencyRecord start(UUID merchantId, String key, String requestHash, Instant now, Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.merchantId = merchantId;
        record.idempotencyKey = key;
        record.requestHash = requestHash;
        record.status = Status.IN_PROGRESS;
        record.createdAt = now;
        record.expiresAt = expiresAt;
        return record;
    }

    void complete(int responseStatus, String responseBody) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
