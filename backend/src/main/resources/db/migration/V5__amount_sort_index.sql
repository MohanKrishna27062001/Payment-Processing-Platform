-- The dashboard can now sort by amount, which means ORDER BY amount_minor with the id tiebreak.
--
-- V3 indexed (merchant_id, amount_minor) for range filtering. Adding id lets the same index satisfy the
-- ordering as well, so sorting by amount needs no sort step: ascending is a forward scan of the index,
-- descending is the same index read backwards. That only works because the id tiebreak takes the same
-- direction as the column being sorted -- see PaymentSort.
DROP INDEX IF EXISTS idx_payments_merchant_amount;
CREATE INDEX idx_payments_merchant_amount ON payments (merchant_id, amount_minor, id);

-- Sorting by created_at and by status are already covered:
--   idx_payments_merchant_created         (merchant_id, created_at DESC, id DESC)
--   idx_payments_merchant_status_created  (merchant_id, status, created_at DESC, id DESC)
