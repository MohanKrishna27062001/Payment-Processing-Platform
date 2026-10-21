package com.mohan.payments.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    /** Merchant-scoped lookup: another merchant's payment is indistinguishable from a missing one. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * Merchant-scoped lookup that takes the row lock ({@code SELECT ... FOR UPDATE}) for the rest of the transaction.
     * <p>
     * Used before any processor call that moves money. {@code @Version} alone is not enough there: it only detects a
     * conflict at commit, after both requests have already called the processor, and a rollback cannot take back
     * a refund the processor has paid out. With the lock, a second request for the same payment waits for the
     * first to commit, then sees the new state and is refused (or proceeds correctly) before calling out.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.merchantId = :merchantId")
    Optional<Payment> findForUpdate(UUID id, UUID merchantId);

    /** As {@link #findForUpdate}, for the settlement worker, which acts on behalf of no particular merchant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(UUID id);

    @Query("select p.status as status, count(p) as total from Payment p group by p.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        PaymentStatus getStatus();

        long getTotal();
    }
}
