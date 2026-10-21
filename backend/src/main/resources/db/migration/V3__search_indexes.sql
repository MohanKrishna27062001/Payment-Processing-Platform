-- Indexes for the payment search filters.
--
-- GET /api/v1/payments filters on merchant (always), status, a created_at range, an amount range, currency,
-- and a free-text q that is either an exact payment id or a case-insensitive prefix of merchant_reference.
-- Results are always ordered by created_at DESC, id DESC and paginated.
--
-- Two notes on method. Every index leads with merchant_id, because tenant isolation is the one predicate that
-- is never optional, and because it makes each merchant's data contiguous. And each index carries the sort
-- columns as a suffix, so the planner can satisfy the filter and the ORDER BY from one scan instead of sorting
-- the matched set afterwards -- which is what makes deep pagination stay cheap.
--
-- In production these would be built with CREATE INDEX CONCURRENTLY outside a transaction. Flyway wraps each
-- migration in one, and at this table size the plain build is instant.

-- 1. The default listing, date-range filtering, and the sort.
--    Replaces the original (merchant_id, created_at DESC): adding id DESC makes the ORDER BY fully
--    index-ordered, so no separate sort step is needed, and the tiebreak keeps page boundaries stable when
--    several payments share a timestamp.
DROP INDEX IF EXISTS idx_payments_merchant_created;
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC, id DESC);

-- 2. Status filtering with the same ordering.
--    Replaces the original (merchant_id, status), which could filter but then had to sort. ?status= is
--    repeatable, so this is typically an index scan per status value with the results merged.
DROP INDEX IF EXISTS idx_payments_merchant_status;
CREATE INDEX idx_payments_merchant_status_created
    ON payments (merchant_id, status, created_at DESC, id DESC);

-- 3. The free-text search on merchant_reference.
--    The original (merchant_id, merchant_reference) could not serve this query at all: the predicate is
--    lower(merchant_reference) LIKE 'x%', so it needs an index on the *expression*, and under any non-C
--    collation a plain b-tree cannot answer a prefix LIKE either. text_pattern_ops compares byte by byte,
--    which is what makes 'x%' an index range scan.
--    Nothing searches merchant_reference case-sensitively, so dropping the old index loses no access path.
DROP INDEX IF EXISTS idx_payments_merchant_reference;
CREATE INDEX idx_payments_merchant_reference_prefix
    ON payments (merchant_id, lower(merchant_reference) text_pattern_ops);

-- 4. Amount-range filtering (minAmount / maxAmount).
--    Worth its own index because an amount range is usually selective; the planner will still sort the
--    matched rows for the ORDER BY, which is the right trade when the filter removes most of them.
CREATE INDEX idx_payments_merchant_amount ON payments (merchant_id, amount_minor);

-- 5. The payments_current{status} gauge runs an unfiltered GROUP BY status every 30 seconds. Without this it
--    is a sequential scan of the whole table on a timer; with it the aggregate is an index-only scan.
CREATE INDEX idx_payments_status ON payments (status);

-- Deliberately not indexed: currency. It is almost always a single value per merchant, so it filters nothing
-- and the planner will use one of the indexes above and apply currency as a filter. Add
-- (merchant_id, currency, created_at DESC, id DESC) only if genuinely multi-currency merchants appear.
-- The exact-id branch of q needs no index: it is a primary-key lookup with merchant_id applied as a filter.

-- 6. Queries added with the outbox operator endpoint and the retention purge. Both are partial indexes, so
--    they stay tiny: only dead letters and only completed jobs are in them.
CREATE INDEX idx_outbox_dead ON outbox_jobs (id DESC) WHERE status = 'DEAD';
CREATE INDEX idx_outbox_done_updated ON outbox_jobs (updated_at) WHERE status = 'DONE';
