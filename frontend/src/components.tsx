import { useEffect, useRef, type ReactNode } from 'react';
import { ApiError } from './api';
import type { PaymentStatus } from './types';

/**
 * Closes a popover when the user clicks away from it or presses Escape — the two things anyone expects of a
 * menu. Returns the ref to put on the element that counts as "inside".
 *
 * Listens on mousedown rather than click so the menu is gone before the thing underneath reacts, and only
 * while the popover is actually open, so a closed menu costs nothing.
 */
export function useDismiss<T extends HTMLElement>(open: boolean, close: () => void) {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent | TouchEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) close();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, close]);
  return ref;
}

export function StatusBadge({ status }: { status: PaymentStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>;
}

/** Shows an API error with its machine code and correlation id, which is what support needs to trace it. */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const problem = apiError?.problem;
  return (
    <div className="banner banner-error" role="alert">
      <strong>{problem?.title ?? 'Something went wrong'}</strong>
      <span>{error instanceof Error ? error.message : String(error)}</span>
      {problem?.errors && (
        <ul>
          {problem.errors.map((e) => (
            <li key={e.field}>
              <code>{e.field}</code>: {e.message}
            </li>
          ))}
        </ul>
      )}
      {(problem?.code || problem?.correlationId) && (
        <small className="muted">
          {problem.code && <>Code: <code>{problem.code}</code> </>}
          {problem.correlationId && <>· Correlation ID: <code>{problem.correlationId}</code></>}
        </small>
      )}
    </div>
  );
}

export function Card({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-header">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="spinner" role="status">
      <span className="spinner-dot" aria-hidden /> {label}
    </div>
  );
}
