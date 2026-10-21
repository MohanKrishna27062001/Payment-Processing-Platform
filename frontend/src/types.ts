export type PaymentStatus =
  | 'PENDING' | 'AUTHORIZED' | 'CAPTURED' | 'SETTLED' | 'FAILED' | 'PARTIALLY_REFUNDED' | 'REFUNDED';

export const ALL_STATUSES: PaymentStatus[] =
  ['PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED'];

export interface MerchantProfile {
  id: string;
  name: string;
  email: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  expiresAt: string;
  merchant: MerchantProfile;
}

export interface Payment {
  id: string;
  status: PaymentStatus;
  amount: number; // minor units
  currency: string;
  merchantReference: string | null;
  card: { brand: string; last4: string } | null;
  processorReference: string | null;
  failureReason: string | null;
  refundedAmount: number; // minor units already returned
  refundableAmount: number; // minor units still returnable
  createdAt: string;
  updatedAt: string;
}

export interface PaymentEvent {
  fromStatus: PaymentStatus | null;
  toStatus: PaymentStatus;
  actorType: 'MERCHANT' | 'SYSTEM';
  actorId: string;
  reason: string | null;
  correlationId: string | null;
  occurredAt: string;
}

export interface PaymentDetail {
  payment: Payment;
  events: PaymentEvent[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** RFC 7807 problem detail as returned by the API. */
export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}
