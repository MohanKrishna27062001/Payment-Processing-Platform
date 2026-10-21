import type { LoginResponse, Page, Payment, PaymentDetail, PaymentStatus, Problem } from './types';

/** An API error carrying the server's problem detail (code, correlationId, field errors). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
  }
}

type UnauthorizedHandler = () => void;
let onUnauthorized: UnauthorizedHandler = () => {};

/** Called whenever the API answers 401 (expired or invalid token) so the app can return to the login page. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  onUnauthorized = handler;
}

async function request<T>(path: string, init: RequestInit & { token?: string; idempotencyKey?: string } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (init.token) headers.set('Authorization', `Bearer ${init.token}`);
  if (init.idempotencyKey) headers.set('Idempotency-Key', init.idempotencyKey);

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError(0, { title: 'Network error', detail: 'Could not reach the server. Is the backend running?' });
  }

  const text = await response.text();
  const body = parseBody(text, response.status);
  if (!response.ok) {
    if (response.status === 401 && init.token) onUnauthorized();
    throw new ApiError(response.status, (body as Problem) ?? {});
  }
  return body as T;
}

export const api = {
  login(email: string, password: string) {
    return request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
  },

  listPayments(token: string, filters: PaymentFilters, page: number, size = 20, sort = 'createdAt,desc') {
    const params = new URLSearchParams();
    filters.statuses.forEach((s) => params.append('status', s));
    if (filters.q) params.set('q', filters.q);
    if (filters.from) params.set('from', new Date(`${filters.from}T00:00:00`).toISOString());
    if (filters.to) params.set('to', endOfDay(filters.to));
    if (filters.minAmount !== undefined) params.set('minAmount', String(filters.minAmount));
    if (filters.maxAmount !== undefined) params.set('maxAmount', String(filters.maxAmount));
    params.set('page', String(page));
    params.set('size', String(size));
    params.set('sort', sort);
    return request<Page<Payment>>(`/api/v1/payments?${params}`, { token });
  },

  getPayment(token: string, id: string) {
    return request<PaymentDetail>(`/api/v1/payments/${encodeURIComponent(id)}`, { token });
  },

  createPayment(token: string, idempotencyKey: string, input: CreatePaymentInput) {
    return request<Payment>('/api/v1/payments', {
      method: 'POST',
      token,
      idempotencyKey,
      body: JSON.stringify({
        amount: input.amount,
        currency: input.currency,
        merchantReference: input.merchantReference || null,
        paymentMethod: { token: input.paymentMethodToken },
      }),
    });
  },

  capture(token: string, id: string, idempotencyKey: string) {
    return request<Payment>(`/api/v1/payments/${encodeURIComponent(id)}/capture`, { method: 'POST', token, idempotencyKey });
  },

  /** Omit `amountMinor` to refund everything still refundable. */
  refund(token: string, id: string, idempotencyKey: string, amountMinor?: number) {
    return request<Payment>(`/api/v1/payments/${encodeURIComponent(id)}/refund`, {
      method: 'POST',
      token,
      idempotencyKey,
      body: amountMinor === undefined ? undefined : JSON.stringify({ amount: amountMinor }),
    });
  },
};

export interface PaymentFilters {
  statuses: PaymentStatus[];
  q?: string;
  from?: string; // yyyy-mm-dd, local
  to?: string; // yyyy-mm-dd, local, inclusive
  minAmount?: number; // minor units
  maxAmount?: number; // minor units
}

export interface CreatePaymentInput {
  amount: number; // minor units
  currency: string;
  merchantReference: string;
  paymentMethodToken: string;
}

/** API responses are JSON, but proxies and filters (e.g. a CORS rejection) can answer with plain text. */
function parseBody(text: string, status: number): unknown {
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return { title: `Request failed (${status})`, detail: text.slice(0, 200) } satisfies Problem;
  }
}

function endOfDay(day: string) {
  const d = new Date(`${day}T00:00:00`);
  d.setDate(d.getDate() + 1); // the API's "to" is exclusive
  return d.toISOString();
}

export function newIdempotencyKey() {
  return crypto.randomUUID();
}
