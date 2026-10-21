import { describe, expect, it } from 'vitest';
import { amountError, currencyDigits, humanize, toMinorUnits } from './format';

describe('toMinorUnits', () => {
  it('converts major amounts to minor units without floating-point error', () => {
    expect(toMinorUnits('10.50', 'USD')).toBe(1050);
    expect(toMinorUnits('10.5', 'USD')).toBe(1050);
    expect(toMinorUnits('0.13', 'USD')).toBe(13);
    expect(toMinorUnits('19.99', 'USD')).toBe(1999);
    expect(toMinorUnits('1000', 'USD')).toBe(100000);
  });

  it('respects zero-decimal currencies', () => {
    expect(currencyDigits('JPY')).toBe(0);
    expect(toMinorUnits('500', 'JPY')).toBe(500);
    expect(toMinorUnits('5.5', 'JPY')).toBeUndefined();
  });

  it('rejects malformed input', () => {
    expect(toMinorUnits('', 'USD')).toBeUndefined();
    expect(toMinorUnits('abc', 'USD')).toBeUndefined();
    expect(toMinorUnits('-5', 'USD')).toBeUndefined();
    expect(toMinorUnits('1.234', 'USD')).toBeUndefined();
  });
});

describe('humanize', () => {
  it('turns machine codes into readable text', () => {
    expect(humanize('insufficient_funds')).toBe('Insufficient funds');
    expect(humanize(null)).toBe('');
  });
});

describe('amountError', () => {
  it('accepts valid amounts and an empty field', () => {
    expect(amountError('', 'USD')).toBeNull();
    expect(amountError('10', 'USD')).toBeNull();
    expect(amountError('10.50', 'USD')).toBeNull();
    expect(amountError('0', 'USD')).toBeNull();
  });

  it('names the problem rather than restating the format', () => {
    expect(amountError('-5', 'USD', 'Min amount')).toBe('Min amount must be a positive number');
    expect(amountError('abc', 'USD', 'Min amount')).toBe('Min amount must contain numbers only, e.g. 10 or 10.50');
    expect(amountError('12a', 'USD')).toContain('numbers only');
    expect(amountError('10.555', 'USD')).toBe('Amount must have at most 2 decimal places, e.g. 10.50');
    expect(amountError('5.5', 'JPY')).toBe('Amount must be a whole number for JPY');
  });

  it('treats every non-numeric character the same way', () => {
    for (const input of ['abc', '12a', '$25', '1,000', '25 00', '10e3', '2+2', '١٢', '25%', '<script>', '👍']) {
      expect(amountError(input, 'USD')).toBe('Amount must contain numbers only, e.g. 10 or 10.50');
    }
  });

  it('explains a second decimal point instead of blaming the decimal places', () => {
    expect(amountError('1.2.3', 'USD')).toBe('Amount can have only one decimal point');
    expect(amountError('..', 'USD')).toBe('Amount can have only one decimal point');
    expect(amountError('.', 'USD')).toContain('numbers only');
  });

  it('allows zero only where zero means something', () => {
    // The filter bar: "min amount 0" is a reasonable thing to ask for.
    expect(amountError('0', 'USD', 'Min amount')).toBeNull();
    expect(amountError('0.00', 'USD', 'Min amount')).toBeNull();
    // The create form: a zero-value charge is not.
    expect(amountError('0', 'USD', 'Amount', false)).toBe('Amount must be greater than zero, e.g. 25.00');
    expect(amountError('0.00', 'USD', 'Amount', false)).toBe('Amount must be greater than zero, e.g. 25.00');
    expect(amountError('0.01', 'USD', 'Amount', false)).toBeNull();
  });
});
