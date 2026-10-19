import { ArrowLeft, CircleAlert, Eye, EyeOff, LockKeyhole } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Img } from '../../components/ui';
import { logo, photos } from '../../data/assets';
import { Link, navigate, usePageMeta } from '../../router';
import { useStore, type Role } from '../../store';

export function Login() {
  usePageMeta('Accesso gestori');
  const { dispatch } = useStore();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [role, setRole] = useState<Role>('titolare');
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email.trim())) next.email = "Inserisci un'email valida, es. gestore@esempio.it.";
    if (password.length < 4) next.password = 'Inserisci la password (in demo bastano 4 caratteri qualsiasi).';
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(next.email ? 'login-email' : 'login-password')?.focus();
      return;
    }
    dispatch({ type: 'session', role });
    navigate('/gestione');
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
          <span><strong>Accesso dimostrativo.</strong> Nessuna verifica reale: qualsiasi email valida e una password di almeno 4 caratteri aprono la demo. Non usare credenziali vere.</span>
        </p>
        <form noValidate onSubmit={submit} className="login__form">
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
          <fieldset className="field">
            <legend>Entra come</legend>
            <div className="role-pick">
              <label className={`role-pick__opt${role === 'titolare' ? ' is-selected' : ''}`}>
                <input type="radio" name="role" checked={role === 'titolare'} onChange={() => setRole('titolare')} />
                <span><strong>Titolare</strong><small>Tutto, compresi personalizzazione del sito, orari e contatti</small></span>
              </label>
              <label className={`role-pick__opt${role === 'staff' ? ' is-selected' : ''}`}>
                <input type="radio" name="role" checked={role === 'staff'} onChange={() => setRole('staff')} />
                <span><strong>Staff</strong><small>Menu, eventi, richieste, foto e avvisi</small></span>
              </label>
            </div>
          </fieldset>
          <button type="submit" className="btn btn--action">Entra nella demo</button>
          <button type="button" className="btn btn--quiet" onClick={() => { setEmail('gestore@esempio.it'); setPassword('demo'); setErrors({}); }}>
            Compila con dati demo
          </button>
        </form>
        <p className="small muted">In produzione: password con hash, blocco dopo tentativi ripetuti, ruoli verificati dal server.</p>
      </main>
    </div>
  );
}
