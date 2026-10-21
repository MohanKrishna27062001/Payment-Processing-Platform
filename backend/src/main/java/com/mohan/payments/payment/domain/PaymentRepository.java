package com.mohan.payments.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    /** Merchant-scoped lookup: another merchant's payment is indistinguishable from a missing one. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Query("select p.status as status, count(p) as total from Payment p group by p.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        PaymentStatus getStatus();

        long getTotal();
    }
}
