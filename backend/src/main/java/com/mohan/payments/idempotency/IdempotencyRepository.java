package com.mohan.payments.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    @Modifying
    @Transactional
    @Query("delete from IdempotencyRecord r where r.expiresAt < :cutoff")
    int deleteExpired(Instant cutoff);
}
