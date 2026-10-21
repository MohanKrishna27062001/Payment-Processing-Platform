package com.mohan.payments.payment;

import java.util.Locale;
import java.util.Map;

import com.mohan.payments.common.error.ApiException;
import org.springframework.data.domain.Sort;

/**
 * Parses the {@code sort} query parameter, e.g. {@code sort=amount,desc}.
 * <p>
 * Sortable columns are an allowlist rather than a pass-through to the entity. A caller who could name any
 * property would be able to sort a large table by an unindexed column, which is a cheap way to make the
 * database do expensive work on request.
 * <p>
 * The {@code id} tiebreak always takes the <b>same direction</b> as the chosen column. That keeps pages stable
 * when values tie, and lets one b-tree index serve both directions: ascending is a forward scan, descending is
 * the same index read backwards. A fixed {@code id DESC} would force a sort step on every ascending query.
 */
public final class PaymentSort {

    /** Default when the caller does not ask: newest first. */
    public static final String DEFAULT = "createdAt,desc";

    /** API name -> entity property. Card and id are deliberately absent: neither has a meaningful order. */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdat", "createdAt",
            "amount", "amountMinor",
            "status", "status");

    private PaymentSort() {
    }

    public static Sort parse(String value) {
        String[] parts = (value == null || value.isBlank() ? DEFAULT : value).split(",", 2);
        String property = SORTABLE.get(parts[0].trim().toLowerCase(Locale.ROOT));
        if (property == null) {
            throw ApiException.badRequest("INVALID_SORT_FIELD", "Invalid sort field",
                    "Sort by one of: createdAt, amount, status.");
        }
        String requested = parts.length > 1 ? parts[1].trim() : "desc";
        if (!requested.equalsIgnoreCase("asc") && !requested.equalsIgnoreCase("desc")) {
            throw ApiException.badRequest("INVALID_SORT_DIRECTION", "Invalid sort direction",
                    "Sort direction must be 'asc' or 'desc', e.g. sort=amount,desc.");
        }
        Sort.Direction direction = requested.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(new Sort.Order(direction, property), new Sort.Order(direction, "id"));
    }
}
