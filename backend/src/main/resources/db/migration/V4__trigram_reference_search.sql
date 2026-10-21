-- Substring search on merchant_reference.
--
-- The search box matches anywhere in the reference, not just the start: typing "913" finds "order-91347".
-- That makes the predicate  lower(merchant_reference) LIKE '%913%'  -- and a leading wildcard rules out a
-- b-tree index completely, whatever operator class it uses. The V3 prefix index can no longer serve this
-- query, so it is replaced rather than kept.
--
-- pg_trgm indexes the three-character sequences of a string, which is exactly what an infix LIKE needs.
-- It is a trusted extension (PostgreSQL 13+), so the database owner can install it without superuser rights.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP INDEX IF EXISTS idx_payments_merchant_reference_prefix;

-- GIN over the trigrams of the lowercased reference. gin_trgm_ops answers LIKE '%x%' and ILIKE alike.
-- merchant_id is not in the index: GIN cannot lead with an equality column the way a b-tree does, so the
-- planner intersects this with the merchant-scoped indexes, or re-checks merchant_id on the matched rows.
CREATE INDEX idx_payments_reference_trgm
    ON payments USING gin (lower(merchant_reference) gin_trgm_ops);

-- Note on short searches: trigrams need at least three characters. A one or two character search cannot use
-- this index and falls back to a scan of the merchant's payments, which is why the UI should ask for three
-- or more characters before searching.
