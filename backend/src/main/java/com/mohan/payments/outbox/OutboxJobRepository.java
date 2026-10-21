package com.mohan.payments.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxJobRepository extends JpaRepository<OutboxJob, Long> {

    /**
     * Locks up to {@code limit} due jobs. {@code SKIP LOCKED} lets several worker instances poll concurrently:
     * each one skips rows another worker has already locked instead of waiting or processing them twice.
     */
    @Query(value = """
            SELECT id FROM outbox_jobs
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueJobIds(Instant now, int limit);

    @Modifying
    @Query("update OutboxJob j set j.status = :status, j.lockedAt = :now, j.updatedAt = :now where j.id in :ids")
    int markLocked(Collection<Long> ids, OutboxJob.Status status, Instant now);

    /** Moves jobs whose worker died mid-processing (locked before {@code cutoff}) back to the queue. */
    @Modifying
    @Query("""
            update OutboxJob j set j.status = :to, j.lockedAt = null, j.updatedAt = :now
            where j.status = :from and j.lockedAt < :cutoff
            """)
    int release(OutboxJob.Status from, OutboxJob.Status to, Instant cutoff, Instant now);

    /** Dead letters, newest first, for the operator endpoint. Capped so one bad deploy cannot return 100k rows. */
    List<OutboxJob> findTop100ByStatusOrderByIdDesc(OutboxJob.Status status);

    /**
     * Drops finished jobs past the retention window. The partial index keeps polling fast regardless, but without
     * this the table grows for the life of the system.
     */
    @Modifying
    @Query("delete from OutboxJob j where j.status = :status and j.updatedAt < :cutoff")
    int deleteCompletedBefore(OutboxJob.Status status, Instant cutoff);

    long countByStatus(OutboxJob.Status status);

    @Query("select min(j.nextAttemptAt) from OutboxJob j where j.status = :status and j.nextAttemptAt <= :now")
    Instant oldestDueAt(OutboxJob.Status status, Instant now);

    List<OutboxJob> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
