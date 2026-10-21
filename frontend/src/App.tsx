import { useCallback, useState } from 'react';
import { Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './auth';
import { useDismiss } from './components';
import { LoginPage } from './pages/LoginPage';
import { PaymentDetailPage } from './pages/PaymentDetailPage';
import { PaymentsPage } from './pages/PaymentsPage';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/payments/:id" element={<PaymentDetailPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/payments" replace />} />
    </Routes>
  );
}

function RequireAuth() {
  const { session, logout } = useAuth();
  const location = useLocation();
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/payments" className="brand">
          <span className="brand-mark" aria-hidden>◆</span> Payments
        </Link>
        <ProfileMenu
          name={session.merchant.name}
          expiresAt={session.expiresAt}
          onLogout={() => logout()}
        />
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

/** "Argyle Payments" -> "AP". Two letters at most, so the avatar stays a circle at any name length. */
function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
}

/**
 * The avatar is the only thing in the bar; everything about the session — who is signed in, when the token
 * expires, how to leave — lives in the menu underneath it. Log out being one click away from the whole
 * header was easy to hit by accident, and this is where people look for it anyway.
 */
function ProfileMenu({ name, expiresAt, onLogout }: { name: string; expiresAt: string; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  const ref = useDismiss<HTMLDivElement>(open, close);
  const expiry = new Date(expiresAt).toLocaleTimeString(undefined, { timeStyle: 'short' });

  return (
    <div className="profile" ref={ref}>
      <button
        type="button"
        className="avatar-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account: ${name}`}
        title={name}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="avatar" aria-hidden>{initialsOf(name)}</span>
        <span className="avatar-caret" aria-hidden>▾</span>
      </button>
      {open && (
        <div className="profile-menu" role="menu">
          <div className="profile-head">
            <span className="avatar avatar-lg" aria-hidden>{initialsOf(name)}</span>
            <div className="profile-id">
              <strong>{name}</strong>
              <small className="muted">Session until {expiry}</small>
            </div>
          </div>
          <div className="profile-sep" role="separator" />
          <button type="button" className="profile-item profile-danger" role="menuitem" onClick={onLogout}>
            <span aria-hidden>⎋</span> Log out
          </button>
        </div>
      )}
    </div>
  );
}
