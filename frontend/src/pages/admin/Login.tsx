import { ArrowLeft, CircleAlert, Eye, EyeOff, LockKeyhole } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { login, uiRole } from '../../api/auth';
import { ApiError } from '../../api/client';
import { Img } from '../../components/ui';
import { logo, photos } from '../../data/assets';
import { Link, navigate, usePageMeta } from '../../router';
import { useStore } from '../../store';

/** Messaggio da mostrare per un errore del backend. Email inesistente e password sbagliata danno lo stesso testo. */
function loginMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_CREDENTIALS') return 'Email o password non corretti.';
    if (error.code === 'RATE_LIMITED' || error.code === 'NETWORK') return error.message;
  }
  return 'Accesso non riuscito. Riprova tra poco.';
}

export function Login() {
  usePageMeta('Accesso gestori');
  const { dispatch, authNotice } = useStore();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});
  const [failure, setFailure] = useState<string | null>(null);
  const failureRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (failure) failureRef.current?.focus();
  }, [failure]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const next: typeof errors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email.trim())) next.email = "Inserisci un'email valida, es. gestore@esempio.it.";
    if (!password) next.password = 'Inserisci la password.';
    setErrors(next);
    setFailure(null);
    if (Object.keys(next).length) {
      document.getElementById(next.email ? 'login-email' : 'login-password')?.focus();
      return;
    }
    setBusy(true);
    try {
      const user = await login(email.trim(), password);
      setPassword('');
      dispatch({ type: 'session', role: uiRole(user.role), user: { displayName: user.displayName, email: user.email } });
      navigate('/gestione');
    } catch (error) {
      setFailure(loginMessage(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <div className="login__media" aria-hidden="true">
        <Img asset={photos.bartenderTagliere} eager decorative />
      </div>
      <main className="login__panel" id="contenuto">
        <Link to="/" className="back-link"><ArrowLeft size={18} aria-hidden="true" /> Torna al sito</Link>
        <Img asset={logo.onDarkSmall} eager className="login__logo" />
        <h1>Area gestori</h1>
        <p className="login__demo" role="note">
          <LockKeyhole size={18} aria-hidden="true" />
          <span><strong>Accesso riservato.</strong> Entra con l'account che ti ha dato il titolare: il ruolo (titolare o staff) è legato all'account.</span>
        </p>
        <form noValidate onSubmit={submit} className="login__form" aria-busy={busy}>
          {(failure ?? authNotice) && (
            <p className="error" role="alert" tabIndex={-1} ref={failureRef} id="login-failure">
              <CircleAlert size={16} aria-hidden="true" />{failure ?? authNotice}
            </p>
          )}
          <div className="field">
            <label htmlFor="login-email">Email</label>
            {errors.email && <p className="error" id="login-email-err"><CircleAlert size={16} aria-hidden="true" />{errors.email}</p>}
            <input id="login-email" className="input" type="email" autoComplete="username" value={email}
              onChange={(e) => setEmail(e.target.value)} aria-invalid={errors.email ? true : undefined}
              aria-describedby={errors.email ? 'login-email-err' : undefined} />
          </div>
          <div className="field">
            <label htmlFor="login-password">Password</label>
            {errors.password && <p className="error" id="login-password-err"><CircleAlert size={16} aria-hidden="true" />{errors.password}</p>}
            <div className="password">
              <input id="login-password" className="input" type={show ? 'text' : 'password'} autoComplete="current-password" value={password}
                onChange={(e) => setPassword(e.target.value)} aria-invalid={errors.password ? true : undefined}
                aria-describedby={errors.password ? 'login-password-err' : undefined} />
              <button type="button" className="icon-btn" aria-pressed={show} aria-label={show ? 'Nascondi password' : 'Mostra password'} onClick={() => setShow((s) => !s)}>
                {show ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              </button>
            </div>
          </div>
          <button type="submit" className="btn btn--action" disabled={busy}>{busy ? 'Accesso in corso…' : 'Accedi'}</button>
        </form>
        <p className="small muted">Dopo 5 tentativi sbagliati l'accesso si blocca per 15 minuti. Password dimenticata? Chiedi al titolare di impostarne una nuova.</p>
      </main>
    </div>
  );
}
