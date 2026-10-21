package com.mohan.payments.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mohan.payments.payment.domain.Payment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    static Specification<Payment> matching(UUID merchantId, PaymentSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Tenant isolation: always the first predicate, never optional.
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
                predicates.add(root.get("status").in(criteria.statuses()));
            }
            if (criteria.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
            }
            if (criteria.createdTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), criteria.createdTo()));
            }
            if (criteria.currency() != null && !criteria.currency().isBlank()) {
                predicates.add(cb.equal(root.get("currency"), criteria.currency().toUpperCase(Locale.ROOT)));
            }
            if (criteria.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amountMinor"), criteria.minAmount()));
            }
            if (criteria.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amountMinor"), criteria.maxAmount()));
            }
            if (criteria.query() != null && !criteria.query().isBlank()) {
                String text = criteria.query().trim();
                UUID id = parseUuid(text);
                // Substring, not prefix: searching "913" should find "order-91347". The leading wildcard
                // rules out a b-tree index, so merchant_reference carries a trigram GIN index instead.
                predicates.add(id != null
                        ? cb.equal(root.get("id"), id)
                        : cb.like(cb.lower(root.get("merchantReference")),
                                "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static UUID parseUuid(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
