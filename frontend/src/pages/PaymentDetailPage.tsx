import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api';
import { useSession } from '../auth';
import { Card, ErrorBanner, Spinner, StatusBadge } from '../components';
import { amountError, formatDateTime, formatMoney, humanize, toMinorUnits } from '../format';
import type { Payment, PaymentDetail } from '../types';

const SETTLEMENT_POLL_MS = 3000;

/**
 * How much can still be returned.
 *
 * When the API response has no refund fields the backend predates partial refunds, so it would ignore an
 * amount and refund everything. {@link supportsPartialRefund} is checked before the amount box is offered --
 * showing an input the server will disregard is worse than not offering it, because the user ends up
 * refunding the full amount believing they refunded part of it.
 */
function refundableOf(p: Payment): number {
  return p.refundableAmount ?? p.amount - (p.refundedAmount ?? 0);
}

function supportsPartialRefund(p: Payment): boolean {
  return p.refundableAmount !== undefined;
}

export function PaymentDetailPage() {
  const { id = '' } = useParams();
  const { token } = useSession();
  const [detail, setDetail] = useState<PaymentDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState<'capture' | 'refund' | null>(null);
  // Stable per action so that a retried click after a network failure is deduplicated by the server.
  const [keys, setKeys] = useState(() => ({ capture: newIdempotencyKey(), refund: newIdempotencyKey() }));
  // Empty means "refund whatever is left", which is what the button did before partial refunds existed.
  const [refundAmount, setRefundAmount] = useState('');

  const load = useCallback(async () => {
    try {
      setDetail(await api.getPayment(token, id));
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [token, id]);

  useEffect(() => {
    load();
  }, [load]);

  // While a captured payment waits for the background settlement worker, poll so the change shows up live.
  const status = detail?.payment.status;
  useEffect(() => {
    if (status !== 'CAPTURED') return;
    const timer = window.setInterval(load, SETTLEMENT_POLL_MS);
    return () => window.clearInterval(timer);
  }, [status, load]);

  async function run(action: 'capture' | 'refund') {
    if (action === 'refund') {
      const typed = refundAmount.trim();
      const problem = amountError(typed, detail!.payment.currency, 'Refund amount');
      if (problem) {
        setActionError(new Error(problem));
        return;
      }
      const minor = typed ? toMinorUnits(typed, detail!.payment.currency) : refundableOf(detail!.payment);
      if (!minor || minor > refundableOf(detail!.payment)) {
        setActionError(new Error(
          `Refund must be between 0.01 and ${formatMoney(refundableOf(detail!.payment), detail!.payment.currency)}.`));
        return;
      }
      const what = formatMoney(minor, detail!.payment.currency);
      if (!window.confirm(`Refund ${what}? This cannot be undone.`)) return;
    }
    setBusy(action);
    setActionError(null);
    try {
      if (action === 'capture') {
        await api.capture(token, id, keys.capture);
      } else {
        const minor = supportsPartialRefund(detail!.payment) && refundAmount.trim()
          ? toMinorUnits(refundAmount, detail!.payment.currency)
          : undefined;
        await api.refund(token, id, keys.refund, minor);
        setRefundAmount('');
      }
      setKeys((k) => ({ ...k, [action]: newIdempotencyKey() }));
      await load();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  }

  if (error && !detail) {
    return (
      <div className="stack">
        <Link to="/payments" className="back">← All payments</Link>
        <ErrorBanner error={error} />
      </div>
    );
  }
  if (!detail) return <Spinner />;

  const { payment, events } = detail;
  const canCapture = payment.status === 'AUTHORIZED';
  const canRefund =
    (payment.status === 'CAPTURED' || payment.status === 'SETTLED' || payment.status === 'PARTIALLY_REFUNDED')
    && refundableOf(payment) > 0;

  return (
    <div className="stack">
      <Link to="/payments" className="back">← All payments</Link>

      <div className="page-title">
        <div>
          <h1 className="amount-title">
            {formatMoney(payment.amount, payment.currency)} <StatusBadge status={payment.status} />
          </h1>
          <p className="muted">
            {payment.merchantReference ?? 'No reference'} · <code>{payment.id}</code>
          </p>
        </div>
        <div className="row">
          {canCapture && (
            <button className="btn btn-primary" disabled={busy !== null} onClick={() => run('capture')}>
              {busy === 'capture' ? 'Capturing…' : 'Capture'}
            </button>
          )}
          {canRefund && (
            <div className="row refund-control">
              {supportsPartialRefund(payment) && (
                <input
                  className="refund-amount"
                  inputMode="decimal"
                  aria-label="Refund amount"
                  placeholder={formatMoney(refundableOf(payment), payment.currency).replace(/[^\d.,]/g, '')}
                  value={refundAmount}
                  onChange={(e) => setRefundAmount(e.target.value)}
                />
              )}
              <button className="btn btn-danger" disabled={busy !== null} onClick={() => run('refund')}>
                {busy === 'refund'
                  ? 'Refunding…'
                  : supportsPartialRefund(payment) && refundAmount.trim()
                    ? 'Refund amount'
                    : 'Refund all'}
              </button>
            </div>
          )}
        </div>
      </div>

      <ErrorBanner error={actionError} />
      {payment.status === 'CAPTURED' && (
        <div className="banner banner-info">
          <Spinner label="Waiting for the settlement worker… this page refreshes automatically." />
        </div>
      )}

      <div className="grid-2">
        <Card title="Details">
          <dl className="details">
            <dt>Status</dt>
            <dd><StatusBadge status={payment.status} /></dd>
            <dt>Amount</dt>
            <dd>{formatMoney(payment.amount, payment.currency)} <span className="muted">({payment.amount} minor units)</span></dd>
            {(payment.refundedAmount ?? 0) > 0 && (
              <>
                <dt>Refunded</dt>
                <dd>
                  {formatMoney(payment.refundedAmount, payment.currency)}
                  <span className="muted"> of {formatMoney(payment.amount, payment.currency)} · </span>
                  {formatMoney(refundableOf(payment), payment.currency)}
                  <span className="muted"> still refundable</span>
                </dd>
              </>
            )}
            <dt>Card</dt>
            <dd>{payment.card ? `${payment.card.brand} •••• ${payment.card.last4}` : '—'}</dd>
            <dt>Merchant reference</dt>
            <dd>{payment.merchantReference ?? '—'}</dd>
            <dt>Processor reference</dt>
            <dd>{payment.processorReference ? <code>{payment.processorReference}</code> : '—'}</dd>
            {payment.failureReason && (
              <>
                <dt>Failure reason</dt>
                <dd className="text-danger">{humanize(payment.failureReason)}</dd>
              </>
            )}
            <dt>Created</dt>
            <dd>{formatDateTime(payment.createdAt)}</dd>
            <dt>Last updated</dt>
            <dd>{formatDateTime(payment.updatedAt)}</dd>
          </dl>
        </Card>

        <Card title="Status history" actions={<span className="muted small">Audit trail</span>}>
          <ol className="timeline">
            {events.map((event, index) => (
              <li key={index} className="timeline-item">
                <div className="timeline-dot" aria-hidden />
                <div>
                  <div className="timeline-head">
                    {event.fromStatus ? (
                      <>
                        <StatusBadge status={event.fromStatus} /> <span className="arrow">→</span>{' '}
                      </>
                    ) : null}
                    <StatusBadge status={event.toStatus} />
                  </div>
                  <div className="timeline-meta">
                    {formatDateTime(event.occurredAt)} · by{' '}
                    <strong>{event.actorType === 'MERCHANT' ? 'merchant' : event.actorId}</strong>
                    {event.reason && <> · {humanize(event.reason)}</>}
                  </div>
                  {event.correlationId && (
                    <div className="timeline-meta muted">
                      Correlation ID <code>{event.correlationId}</code>
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </Card>
      </div>
    </div>
  );
}
