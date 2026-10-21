/** Number of decimal places the currency uses (2 for USD, 0 for JPY...). */
export function currencyDigits(currency: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/** Formats an amount in minor units, e.g. (1050, 'USD') -> "$10.50". */
export function formatMoney(minor: number, currency: string): string {
  const digits = currencyDigits(currency);
  const value = minor / 10 ** digits;
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(value);
  } catch {
    return `${value.toFixed(digits)} ${currency}`;
  }
}

/** Parses a user-entered major amount ("10.50") into minor units (1050). Returns undefined if invalid. */
export function toMinorUnits(input: string, currency: string): number | undefined {
  const trimmed = input.trim();
  const digits = currencyDigits(currency);
  const pattern = digits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${digits}})?$`);
  if (!pattern.test(trimmed)) return undefined;
  const [whole, fraction = ''] = trimmed.split('.');
  return Number(whole) * 10 ** digits + Number(fraction.padEnd(digits, '0') || 0);
}

/**
 * Explains why a user-entered amount is not acceptable, or null when it is fine. Separate from
 * {@link toMinorUnits}, which only answers yes/no: the field should say what is wrong with what was typed
 * rather than restating the expected format every time.
 *
 * An empty string is not an error here; callers decide whether the field is required.
 *
 * `allowZero` is true for the filter bar, where "min amount 0" is a sensible thing to ask for, and false
 * for the create form, where a zero-value charge is not. Checking it here rather than at submit time means
 * the field explains itself while the user is still in it, and there is only ever one message to show.
 */
export function amountError(input: string, currency: string, label = 'Amount', allowZero = true): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith('-')) return `${label} must be a positive number`;
  // Anything that is not a digit or a dot: letters, currency symbols, thousands separators, spaces,
  // arithmetic signs, emoji. One message covers them all, because the fix is always the same.
  if (/[^\d.]/.test(trimmed)) return `${label} must contain numbers only, e.g. 10 or 10.50`;
  // "1.2.3" would otherwise fall through to the decimal-places message, which explains the wrong problem.
  if ((trimmed.match(/\./g) ?? []).length > 1) return `${label} can have only one decimal point`;
  if (trimmed === '.') return `${label} must contain numbers only, e.g. 10 or 10.50`;
  const minor = toMinorUnits(trimmed, currency);
  if (minor === undefined) {
    const digits = currencyDigits(currency);
    return digits === 0
      ? `${label} must be a whole number for ${currency}`
      : `${label} must have at most ${digits} decimal places, e.g. 10.50`;
  }
  if (minor === 0 && !allowZero) return `${label} must be greater than zero, e.g. 25.00`;
  return null;
}

/** Today in the local timezone as yyyy-mm-dd, for date inputs. Not toISOString(), which uses UTC. */
export function todayIso(): string {
  return new Date().toLocaleDateString('en-CA');
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** "insufficient_funds" -> "Insufficient funds" */
export function humanize(code: string | null | undefined): string {
  if (!code) return '';
  const text = code.replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}
