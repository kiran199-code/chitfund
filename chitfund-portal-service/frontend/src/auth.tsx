import { useState } from 'react';
import { PortalConfig } from './api';
import { BRAND, Icon, Logo } from './brand';
import GoogleButton from './google';

// Split sign-in / register page: photo-style panel on the left (illustration slot), form on the right. Sign-in is Google only; there is no password.

const BENEFITS: [string, string, string][] = [
  ['ticket', 'Choose a plan that fits your goal', 'Education, home, wedding, retirement and more.'],
  ['rupee', 'See every payment', 'What you owe, what is coming, what we have recorded.'],
  ['gavel', 'Bid in auctions from your phone', 'The system checks the limits for every bid.'],
  ['phone', 'A real person to call', 'Your organiser’s number is on every page.'],
  ['check', 'No password to remember', 'You sign in with Google. We never see your Google password.'],
];

export default function AuthPage(props: {
  mode: 'login' | 'register'; config: PortalConfig | null; configError: string | null; busy: boolean; error: string | null;
  onCredential: (t: string) => void; plan?: string; invited?: boolean;
}) {
  const reg = props.mode === 'register';
  const [agree, setAgree] = useState(false);
  const { config } = props;
  const suffix = props.plan ? `?plan=${encodeURIComponent(props.plan)}` : '';
  return (
    <div className="auth">
      <aside className="auth-left">
        <a className="auth-back" href="#/"><Icon name="arrow" size={16} /> Back to plans</a>
        <div className="auth-photo" aria-hidden="true">
          {/* photo slot: a real photograph can replace this illustration */}
          <svg viewBox="0 0 400 220" width="100%" height="100%" preserveAspectRatio="xMidYMid slice">
            <rect width="400" height="220" fill="#1d1f23" />
            <circle cx="330" cy="40" r="90" fill="#d71920" opacity=".28" />
            <circle cx="60" cy="210" r="110" fill="#d71920" opacity=".18" />
            <g fill="#2b2d31"><rect x="20" y="120" width="40" height="100" /><rect x="70" y="90" width="50" height="130" /><rect x="130" y="130" width="36" height="90" /><rect x="250" y="100" width="46" height="120" /><rect x="306" y="140" width="60" height="80" /></g>
            <g fill="#c9cbcf"><circle cx="190" cy="130" r="16" /><path d="M164 200q0-42 26-42t26 42z" /><circle cx="226" cy="132" r="13" fill="#8b8f94" /><path d="M205 190q0-34 21-34t21 34z" fill="#8b8f94" /></g>
            <g><circle cx="188" cy="62" r="20" fill="#fff" transform="translate(0 6)" /><path d="M179 68l6 6 11-12" stroke="#d71920" strokeWidth="4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></g>
          </svg>
        </div>
        <h1>{reg ? 'Start saving the honest way.' : 'Welcome back.'}</h1>
        <p className="auth-tag">{BRAND.tagline}</p>
        <ul className="benefits">
          {BENEFITS.map(([ic, t, d]) => (
            <li key={t}><span className="ic"><Icon name={ic} size={20} /></span><div><strong>{t}</strong><span>{d}</span></div></li>
          ))}
        </ul>
      </aside>
      <main className="auth-right">
        <div className="auth-card">
          <Logo />
          <h2 className="auth-title">{reg ? 'CREATE ACCOUNT' : 'SIGN IN'}</h2>
          {props.plan && <p className="note small">Sign in to send your request for the plan you chose. Nothing is charged.</p>}
          {props.invited && <p className="note small">Sign in with the Google account for the e-mail address your organiser has for you.</p>}
          {reg && (
            <label className="agree">
              <input type="checkbox" checked={agree} onChange={e => setAgree(e.target.checked)} />
              <span>I understand that joining a chit is a financial commitment and I agree to the group's terms, which I will read with the organiser before paying.</span>
            </label>
          )}
          {props.configError && <p className="error">{props.configError}</p>}
          {config && !config.googleClientId && <p className="error">Sign-in is not set up yet. Please contact your organiser.</p>}
          {config && config.googleClientId && (!reg || agree) && <div className="auth-google"><GoogleButton clientId={config.googleClientId} onCredential={props.onCredential} /></div>}
          {config && config.googleClientId && reg && !agree && <button className="btn btn-block" disabled>Continue with Google</button>}
          {!config && !props.configError && <p className="muted">Loading...</p>}
          {props.busy && <p className="muted" role="status">Signing you in...</p>}
          {props.error && <p className="error" role="alert">{props.error}</p>}
          <div className="or"><span>OR</span></div>
          <p className="auth-alt">
            {reg
              ? <>Already have an account? <a href={'#/login' + suffix}>SIGN IN</a></>
              : <>New here? <a href={'#/register' + suffix}>CREATE ACCOUNT</a></>}
          </p>
          <p className="small muted auth-note">There is no password to create, change or forget. Your account is your Google account.</p>
        </div>
        <p className="auth-foot small muted">{BRAND.name}</p>
      </main>
    </div>
  );
}
