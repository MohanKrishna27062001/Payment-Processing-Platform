import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setUnauthorizedHandler } from './api';
import type { MerchantProfile } from './types';

interface Session {
  token: string;
  expiresAt: string;
  merchant: MerchantProfile;
}

interface AuthContextValue {
  session: Session | null;
  login: (email: string, password: string) => Promise<void>;
  logout: (reason?: string) => void;
  logoutReason: string | null;
}

const STORAGE_KEY = 'payments.session';
const AuthContext = createContext<AuthContextValue | null>(null);

// sessionStorage: the token survives a page refresh but not closing the tab. Tradeoff documented in the README
// (an httpOnly cookie would hide it from scripts entirely, at the cost of CSRF handling).
function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt).getTime() > Date.now() ? session : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(loadSession);
  const [logoutReason, setLogoutReason] = useState<string | null>(null);

  const logout = useCallback((reason?: string) => {
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable */
    }
    setSession(null);
    setLogoutReason(reason ?? null);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const response = await api.login(email, password);
    const next = { token: response.accessToken, expiresAt: response.expiresAt, merchant: response.merchant };
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      /* storage unavailable: session lasts until refresh */
    }
    setLogoutReason(null);
    setSession(next);
  }, []);

  // The server rejects expired tokens with 401; also log out proactively when the token's expiry passes.
  useEffect(() => {
    setUnauthorizedHandler(() => logout('Your session has expired. Please log in again.'));
    if (!session) return;
    const remaining = new Date(session.expiresAt).getTime() - Date.now();
    const timer = window.setTimeout(() => logout('Your session has expired. Please log in again.'), Math.max(remaining, 0));
    return () => window.clearTimeout(timer);
  }, [session, logout]);

  const value = useMemo(() => ({ session, login, logout, logoutReason }), [session, login, logout, logoutReason]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

/** For pages behind login: the session is guaranteed to exist. */
export function useSession(): Session {
  const { session } = useAuth();
  if (!session) throw new Error('No active session');
  return session;
}
