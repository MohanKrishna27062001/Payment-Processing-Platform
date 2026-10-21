import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, newIdempotencyKey, type PaymentFilters } from '../api';
import { useSession } from '../auth';
import { Card, ErrorBanner, Spinner, StatusBadge, useDismiss } from '../components';
import { amountError, formatDateTime, formatMoney, humanize, shortId, todayIso, toMinorUnits } from '../format';
import { ALL_STATUSES, type Page, type Payment, type PaymentStatus } from '../types';

const PAGE_SIZE = 10;
const DEFAULT_SORT = 'createdAt,desc';

/** Columns the API will sort by. Card and ID are absent: neither has an order that means anything. */
const SORTABLE_COLUMNS: ReadonlyArray<{ key: string; label: string; numeric?: boolean }> = [
  { key: 'createdAt', label: 'Created' },
  { key: 'amount', label: 'Amount', numeric: true },
  { key: 'status', label: 'Status' },
];

/** Filters live in the URL, so a filtered view can be refreshed, bookmarked or shared. */
function filtersFromParams(params: URLSearchParams): PaymentFilters {
  const amount = (key: string) => {
    const value = params.get(key);
    return value === null || value === '' ? undefined : Number(value);
  };
  return {
    statuses: params.getAll('status') as PaymentStatus[],
    q: params.get('q') ?? undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    minAmount: amount('minAmount'),
    maxAmount: amount('maxAmount'),
  };
}

export function PaymentsPage() {
  const { token } = useSession();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const filters = useMemo(() => filtersFromParams(params), [params]);
  const page = Number(params.get('page') ?? 0);
  // Sort travels in the URL like the filters, so a sorted view survives a refresh and can be shared.
  const sort = params.get('sort') ?? DEFAULT_SORT;

  const [data, setData] = useState<Page<Payment> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await api.listPayments(token, filters, page, PAGE_SIZE, sort));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [token, filters, page, sort]);

  useEffect(() => {
    load();
  }, [load]);

  function sortBy(column: string) {
    const [active, direction] = sort.split(',');
    const next = new URLSearchParams(params);
    // A new column starts at descending (largest or newest first, which is what people look for);
    // clicking the column already in use flips it.
    next.set('sort', `${column},${active === column && direction === 'desc' ? 'asc' : 'desc'}`);
    next.set('page', '0');
    setParams(next);
  }

  function goToPage(next: number) {
    const updated = new URLSearchParams(params);
    updated.set('page', String(next));
    setParams(updated);
  }

  return (
    <div className="stack">
      <div className="page-title">
        <div>
          <h1>Payments</h1>
          <p className="muted">Search, filter and inspect your payments.</p>
        </div>
        <div className="row">
          <button className="btn btn-ghost" onClick={load} disabled={loading}>
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? 'Close' : 'Create payment'}
          </button>
        </div>
      </div>

      {showCreate && (
        <CreatePaymentForm
          token={token}
          onCreated={(payment) => navigate(`/payments/${payment.id}`)}
        />
      )}

      <FilterBar filters={filters} onApply={(next) => setParams(next)} />

      <Card>
        <ErrorBanner error={error} />
        {loading && !data ? (
          <Spinner />
        ) : data && data.items.length === 0 ? (
          <p className="empty">No payments match these filters.</p>
        ) : (
          data && (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    {SORTABLE_COLUMNS.map(({ key, label, numeric }) => {
                      const [active, direction] = sort.split(',');
                      const on = active === key;
                      return (
                        <th
                          key={key}
                          className={numeric ? 'num' : undefined}
                          aria-sort={on ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                        >
                          <button type="button" className="sort-btn" onClick={() => sortBy(key)}
                                  title={`Sort by ${label.toLowerCase()}`}>
                            {label}
                            <span aria-hidden="true" className={on ? 'sort-on' : 'sort-off'}>
                              {on ? (direction === 'asc' ? '\u25B2' : '\u25BC') : '\u2195'}
                            </span>
                          </button>
                        </th>
                      );
                    })}
                    <th>Reference</th>
                    <th>Card</th>
                    <th>ID</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((p) => (
                    <tr
                      key={p.id}
                      className="clickable"
                      tabIndex={0}
                      onClick={() => navigate(`/payments/${p.id}`)}
                      onKeyDown={(e) => e.key === 'Enter' && navigate(`/payments/${p.id}`)}
                    >
                      <td>{formatDateTime(p.createdAt)}</td>
                      <td className="num">{formatMoney(p.amount, p.currency)}</td>
                      <td>
                        <StatusBadge status={p.status} />
                        {/* Only FAILED payments carry a reason, so it sits with the status rather than
                            in a column that would be empty on almost every row. */}
                        {p.failureReason && (
                          <div className="muted small text-danger">{humanize(p.failureReason)}</div>
                        )}
                      </td>
                      <td>{p.merchantReference ?? <span className="muted">—</span>}</td>
                      <td>{p.card ? `${p.card.brand} •••• ${p.card.last4}` : <span className="muted">—</span>}</td>
                      <td>
                        <code title={p.id}>{shortId(p.id)}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
        {data && data.totalItems > 0 && (
          <div className="pagination">
            <span className="muted">
              Page {data.page + 1} of {Math.max(data.totalPages, 1)} · {data.totalItems} payment
              {data.totalItems === 1 ? '' : 's'}
            </span>
            <div className="row">
              <button className="btn btn-ghost" disabled={page <= 0 || loading} onClick={() => goToPage(page - 1)}>
                Previous
              </button>
              <button
                className="btn btn-ghost"
                disabled={page + 1 >= data.totalPages || loading}
                onClick={() => goToPage(page + 1)}
              >
                Next
              </button>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}

/** "PARTIALLY_REFUNDED" -> "Partially refunded", for anywhere a status is read rather than scanned. */
function statusLabel(status: PaymentStatus): string {
  const words = status.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * One labelled field. The label is a span rather than bare text so it can be styled on its own — the forms
 * pick up the accent face and weight from `.field-label`, and `plain` opts a field out of it.
 */
function Field({
  label,
  labelId,
  plain,
  className,
  hint,
  children,
}: {
  label: string;
  labelId?: string;
  plain?: boolean;
  className?: string;
  hint?: string | null;
  children: ReactNode;
}) {
  const classes = className ? `field ${className}` : 'field';
  const body = (
    <>
      <span id={labelId} className={plain ? 'field-label field-label-plain' : 'field-label'}>
        {label}
      </span>
      {children}
      {hint && <span className="field-error">{hint}</span>}
    </>
  );
  // A wrapping <label> is right for a real form control. A button is not one — `for` does not bind to it —
  // so the status picker gets a plain div and names itself with aria-labelledby instead.
  return labelId ? <div className={classes}>{body}</div> : <label className={classes}>{body}</label>;
}

/**
 * The status filter as one control instead of a row of eight chips. The chips were a single attribute
 * wearing eight hit targets, which both wrapped unpredictably and made "no statuses selected" look the
 * same as "I haven't got to that yet". A closed dropdown states the current selection in one line.
 */
function StatusPicker({
  selected,
  onChange,
}: {
  selected: PaymentStatus[];
  onChange: (next: PaymentStatus[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const ref = useDismiss<HTMLDivElement>(open, close);

  const summary =
    selected.length === 0
      ? 'All statuses'
      : selected.length === 1
        ? statusLabel(selected[0])
        : `${selected.length} statuses`;

  function toggle(status: PaymentStatus) {
    onChange(selected.includes(status) ? selected.filter((s) => s !== status) : [...selected, status]);
  }

  return (
    <div className="picker" ref={ref}>
      <button
        type="button"
        className={`picker-btn ${selected.length > 0 ? 'picker-btn-on' : ''}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-labelledby="status-filter-label"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="picker-summary">{summary}</span>
        {selected.length > 0 && <span className="picker-count">{selected.length}</span>}
        <span className="picker-caret" aria-hidden>▾</span>
      </button>
      {open && (
        <div className="picker-menu" role="listbox" aria-multiselectable="true" aria-label="Status">
          {ALL_STATUSES.map((s) => (
            <label key={s} className="picker-option" role="option" aria-selected={selected.includes(s)}>
              <input type="checkbox" checked={selected.includes(s)} onChange={() => toggle(s)} />
              <StatusBadge status={s} />
            </label>
          ))}
          <div className="picker-foot">
            <button type="button" className="link-btn" onClick={() => onChange([])} disabled={selected.length === 0}>
              Clear
            </button>
            <button
              type="button"
              className="link-btn"
              onClick={() => onChange([...ALL_STATUSES])}
              disabled={selected.length === ALL_STATUSES.length}
            >
              Select all
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function FilterBar({ filters, onApply }: { filters: PaymentFilters; onApply: (params: URLSearchParams) => void }) {
  const [q, setQ] = useState(filters.q ?? '');
  const [statuses, setStatuses] = useState<PaymentStatus[]>(filters.statuses);
  const [from, setFrom] = useState(filters.from ?? '');
  const [to, setTo] = useState(filters.to ?? '');
  const [minAmount, setMinAmount] = useState(filters.minAmount !== undefined ? String(filters.minAmount / 100) : '');
  const [maxAmount, setMaxAmount] = useState(filters.maxAmount !== undefined ? String(filters.maxAmount / 100) : '');

  // Checked on every keystroke rather than only on Apply: a contradictory range is a mistake the user
  // should see while they are still looking at the field they mistyped.
  const today = todayIso();
  const minMinor = minAmount.trim() ? toMinorUnits(minAmount, 'USD') : undefined;
  const maxMinor = maxAmount.trim() ? toMinorUnits(maxAmount, 'USD') : undefined;
  // A bad single value belongs under its own field; only a problem *between* two fields goes in the banner,
  // because there is no one field to hang it on.
  const minProblem = amountError(minAmount, 'USD', 'Min amount');
  const maxProblem = amountError(maxAmount, 'USD', 'Max amount');
  const crossFieldError =
    minMinor !== undefined && maxMinor !== undefined && maxMinor < minMinor
      ? 'Max amount must be greater than or equal to min amount'
      : from && to && to < from
        ? 'The To date must be on or after the From date'
        : from > today || to > today
          ? 'Dates cannot be in the future'
          : null;
  const rangeError = minProblem ?? maxProblem ?? crossFieldError;

  function submit(event?: FormEvent) {
    event?.preventDefault();
    // Every reason to refuse is derived from the current field values above, so there is no error to store
    // and later forget to clear: fix the field and the message goes with it.
    if (rangeError) return;
    const next = new URLSearchParams();
    statuses.forEach((s) => next.append('status', s));
    if (q.trim()) next.set('q', q.trim());
    if (from) next.set('from', from);
    if (to) next.set('to', to);
    if (minMinor !== undefined) next.set('minAmount', String(minMinor));
    if (maxMinor !== undefined) next.set('maxAmount', String(maxMinor));
    onApply(next);
  }

  function reset() {
    setQ('');
    setStatuses([]);
    setFrom('');
    setTo('');
    setMinAmount('');
    setMaxAmount('');
    onApply(new URLSearchParams());
  }

  return (
    <Card>
      <form className="filters" onSubmit={submit} noValidate>
        <Field label="Search">
          <input placeholder="Payment ID or merchant reference" value={q} onChange={(e) => setQ(e.target.value)} />
        </Field>
        {/* `date-empty` greys out the mm/dd/yyyy the browser shows in an empty date field. A date input has
            no placeholder to style, and colouring the input unconditionally would grey a real date too, so
            the class is driven by the value. */}
        <Field label="From">
          <input type="date" className={from ? undefined : 'date-empty'} max={to || today}
                 value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="To">
          <input type="date" className={to ? undefined : 'date-empty'} min={from || undefined} max={today}
                 value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
        <Field label="Min amount" hint={minProblem}>
          <input inputMode="decimal" placeholder="0.00" aria-invalid={minProblem !== null}
                 value={minAmount} onChange={(e) => setMinAmount(e.target.value)} />
        </Field>
        <Field label="Max amount" hint={maxProblem}>
          <input inputMode="decimal" placeholder="0.00" aria-invalid={maxProblem !== null}
                 value={maxAmount} onChange={(e) => setMaxAmount(e.target.value)} />
        </Field>
        <Field label="Status" labelId="status-filter-label" className="status-field">
          <StatusPicker selected={statuses} onChange={setStatuses} />
        </Field>
        <div className="row filter-actions">
          <button type="button" className="btn btn-ghost" onClick={reset}>
            Reset
          </button>
          <button type="submit" className="btn btn-primary" disabled={rangeError !== null}>
            Apply filters
          </button>
        </div>
      </form>
      {crossFieldError && <div className="banner banner-error" role="alert">{crossFieldError}</div>}
    </Card>
  );
}

const TEST_CARDS = [
  { token: 'tok_visa', label: 'Visa •••• 4242 (approves)' },
  { token: 'tok_mastercard', label: 'Mastercard •••• 4444 (approves)' },
  { token: 'tok_decline', label: 'Declined card (insufficient funds)' },
  { token: 'tok_processor_error', label: 'Processor outage (503, retryable)' },
];

/**
 * A merchant reference for the demo form, e.g. "order-481207".
 *
 * The six digits are the current millisecond modulo 1,000,000, not a random number. Two references drawn at
 * random from a million values collide about half the time by the thousandth payment; taking them from the
 * clock means anything created within the same ~16.7 minutes is guaranteed distinct, which covers any
 * realistic run of the form.
 *
 * Not a sequence: this runs in the browser, so there is no single authority issuing numbers -- two tabs would
 * hand out the same one, and a public sequence leaks how many orders have been placed. Sequential is right
 * when one database owns the counter, which is why a real merchant's order ids often look that way.
 *
 * The reference is deliberately NOT unique in the schema: one order can legitimately have several payments
 * (a retry after a decline, a deposit then the balance). Duplicate *charges* are prevented by the
 * Idempotency-Key, which is a different question.
 */
function newReference() {
  return `order-${String(Date.now() % 1_000_000).padStart(6, '0')}`;
}

function CreatePaymentForm({ token, onCreated }: { token: string; onCreated: (payment: Payment) => void }) {
  // Empty rather than pre-filled: the placeholder shows the expected format without a value to clear.
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [reference, setReference] = useState(newReference);
  const [card, setCard] = useState(TEST_CARDS[0].token);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  // Shown while typing, so a bad amount is flagged at the field rather than after pressing Create.
  // `allowZero: false` — a zero-value charge is not a thing, and catching it here rather than at submit
  // time means the field states the problem instead of a banner appearing after the fact.
  const amountProblem = amountError(amount, currency, 'Amount', false);
  // One key per logical payment: re-submitting after a failure or a lost response reuses it, so the server
  // returns the original result instead of charging twice. A new key is minted only after success.
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);

  /**
   * Any edit retires the last submit's error.
   *
   * Without this, a rejected submit left its banner on screen while the user fixed the field, so a corrected
   * form showed a live field error *and* a stale banner describing a value that was no longer in the box.
   * The field problem is derived from the current value and needs no clearing; the submit error does, and
   * this is the one place every field goes through.
   */
  function edit<T>(set: (value: T) => void) {
    return (value: T) => {
      setError(null);
      set(value);
    };
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    // The field already says what is wrong and the button is disabled; this is the keyboard-submit path.
    if (amountProblem) return;
    const minor = toMinorUnits(amount, currency);
    if (minor === undefined || minor === 0) return;
    setSubmitting(true);
    setError(null);
    try {
      const payment = await api.createPayment(token, idempotencyKey, {
        amount: minor,
        currency,
        merchantReference: reference,
        paymentMethodToken: card,
      });
      setIdempotencyKey(newIdempotencyKey());
      setReference(newReference());
      onCreated(payment);
    } catch (e) {
      setError(e);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card title="Create payment">
      <form className="create-form" onSubmit={submit} noValidate>
        <Field label="Amount" hint={amountProblem}>
          <input inputMode="decimal" placeholder="25.00" aria-invalid={amountProblem !== null}
                 value={amount} onChange={(e) => edit(setAmount)(e.target.value)} />
        </Field>
        <Field label="Currency">
          <select value={currency} onChange={(e) => edit(setCurrency)(e.target.value)}>
            <option>USD</option>
            <option>EUR</option>
            <option>GBP</option>
          </select>
        </Field>
        <Field label="Merchant reference">
          <input value={reference} onChange={(e) => edit(setReference)(e.target.value)} maxLength={100} />
        </Field>
        <Field label="Test card">
          <select value={card} onChange={(e) => edit(setCard)(e.target.value)}>
            {TEST_CARDS.map((c) => (
              <option key={c.token} value={c.token}>
                {c.label}
              </option>
            ))}
          </select>
        </Field>
        <div className="row filter-actions">
          <button className="btn btn-primary" type="submit"
                  disabled={submitting || amountProblem !== null || amount.trim() === ''}>
            {submitting ? 'Creating…' : 'Create payment'}
          </button>
        </div>
      </form>
      {/* Only ever one message: a live field problem is already shown under the field it belongs to, so the
          submit banner stays out of the way until the form is in a state worth submitting again. */}
      {!amountProblem && <ErrorBanner error={error} />}
    </Card>
  );
}
