import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, Member, Offer, PortalConfig, Site, api } from './api';
import { BRAND, CoinsArt, GavelArt, HeroArt, Icon, LampArt, Logo } from './brand';
import AuthPage from './auth';
import { PlanBrowser, PlansPage } from './plans';
import { Approvals, Auctions, Dashboard, More, NoAccount, OfferCards, Offers, Payments, Tickets, useLoad } from './member';
import { Overview } from './api';

type Session = { state: 'loading' } | { state: 'out' } | { state: 'in'; member: Member };

const SIGN_IN_ERRORS: Record<string, string> = {
  INVALID_ID_TOKEN: 'Google sign-in could not be verified. Please try again.',
  GOOGLE_ACCOUNT_MISMATCH: 'This e-mail address is already linked to a different Google account. Please sign in with the Google account you used before.',
  MEMBER_BLOCKED: 'This account cannot sign in. Please contact your organiser.',
  TOO_MANY_REQUESTS: 'Too many attempts. Please wait a minute and try again.',
  SIGN_IN_NOT_CONFIGURED: 'Sign-in is not set up yet. Please contact your organiser.',
};

function useRoute(): { path: string; query: URLSearchParams } {
  const read = () => {
    const h = window.location.hash.replace(/^#\/?/, '');
    const [path, q] = h.split('?');
    return { path: path ?? '', query: new URLSearchParams(q ?? '') };
  };
  const [r, setR] = useState(read);
  useEffect(() => {
    const on = () => { setR(read()); window.scrollTo({ top: 0 }); };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return r;
}

const telHref = (p: string) => 'tel:' + p.replace(/[^+\d]/g, '');

export default function App() {
  const [config, setConfig] = useState<PortalConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [site, setSite] = useState<Site | null>(null);
  const [session, setSession] = useState<Session>({ state: 'loading' });
  const [signInError, setSignInError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const route = useRoute();

  useEffect(() => {
    api.config().then(setConfig).catch(e => setConfigError(e instanceof Error ? e.message : String(e)));
    api.site().then(setSite).catch(() => setSite(null));
    api.me().then(member => setSession({ state: 'in', member })).catch(() => setSession({ state: 'out' }));
  }, []);

  const onCredential = useCallback(async (idToken: string) => {
    setBusy(true);
    setSignInError(null);
    try {
      const m = await api.signIn(idToken);
      const plan = new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('plan');
      window.location.hash = plan ? `/plans?join=${encodeURIComponent(plan)}` : '/dashboard';
      setSession({ state: 'in', member: m });
    } catch (e) {
      const code = e instanceof ApiError ? e.code : '';
      setSignInError(SIGN_IN_ERRORS[code] ?? (e instanceof Error ? e.message : 'Sign-in failed.'));
    } finally {
      setBusy(false);
    }
  }, []);

  async function signOut() {
    try { await api.signOut(); } catch { /* the cookie may already be gone */ }
    setSession({ state: 'out' });
    setSignInError(null);
  }

  if (session.state === 'loading') return <div className="shell"><div className="wrap main"><p className="muted" role="status">Loading...</p></div></div>;

  if (session.state === 'out') {
    const plan = route.query.get('plan') ?? undefined;
    const authMode = route.path === 'login' || route.path === 'register' ? route.path : null;
    return authMode ? (
      <AuthPage mode={authMode} config={config} configError={configError} busy={busy} error={signInError} onCredential={onCredential} plan={plan} />
    ) : (
      <div className="shell">
        <Landing />
        <Footer site={site} />
      </div>
    );
  }

  return <Portal member={session.member} site={site} onSignOut={signOut} />;
}

function Who({ member, onSignOut }: { member: Member; onSignOut: () => void }) {
  const name = member.displayName ?? member.email;
  const initials = name.split(/[ @.]/).filter(Boolean).slice(0, 2).map(x => x.charAt(0).toUpperCase()).join('') || '?';
  return (
    <div className="who">
      <div className="who-chip">
        <span className="avatar" title={member.email}>{initials}</span>
        <span className="who-text"><span className="who-name">{name}</span><span className="who-sub"><i /> Signed in</span></span>
      </div>
      <button className="signout" onClick={onSignOut}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" /></svg> Sign out
      </button>
    </div>
  );
}

function Footer({ site }: { site: Site | null }) {
  return (
    <footer className="foot">
      <div className="wrap foot-in">
        <div><Logo compact light /><p>{BRAND.tagline}</p></div>
        <div className="foot-help">
          <strong>Need help?</strong>
          <p>
            {site?.helpPhone ? <>Call your organiser: <a href={telHref(site.helpPhone)}>{site.helpPhone}</a>{site.helpHours ? `. ${site.helpHours}` : ''}</> : 'Ask your organiser for help.'}
          </p>
          {site?.officeAddress && <p>{site.officeAddress}</p>}
          <p>Sign-in is handled by Google. No password is stored here.</p>
          <p className="foot-copy">&copy; {new Date().getFullYear()} {BRAND.name}</p>
        </div>
      </div>
    </footer>
  );
}

// ---------------------------------------------------------------- signed out

function Landing() {
  const invest = (p: { groupCode: string }) => { window.location.hash = `/login?plan=${encodeURIComponent(p.groupCode)}`; };
  return (
    <>
      <header className="land-top">
        <div className="wrap top-in">
          <Logo compact />
          <div className="top-actions"><a className="btn btn-ghost" href="#/login">Sign in</a><a className="btn" href="#/register">Register</a></div>
        </div>
      </header>
      <main>
        <div className="hero-band"><div className="wrap hero">
          <div>
            <h1>Honest dealings.<br /><span>Happy savers.</span></h1>
            <p className="lead">Pick a chit plan for your goal, send a request, and follow every payment and auction in one place.</p>
            <div className="actions"><a className="btn btn-gold" href="#/register">Create account</a><a className="btn btn-ghost" style={{ color: '#fff', borderColor: '#8b8f94' }} href="#/login">Sign in</a></div>
          </div>
          <HeroArt />
        </div></div>
        <div className="shelf"><div className="wrap"><PlanBrowser onInvest={invest} /></div></div>
        <PublicOffers />
        <HowItWorks />
      </main>
    </>
  );
}

function PublicOffers() {
  const s = useLoad<Offer[]>(api.publicOffers);
  if (!s.data || s.data.length === 0) return null;
  return (
    <section className="band">
      <div className="wrap"><h2>Offers for you</h2><OfferCards offers={s.data} /></div>
    </section>
  );
}

function HowItWorks() {
  return (
    <>
      <section className="band band-alt">
        <div className="wrap">
          <h2>How a chit works, in three steps</h2>
          <div className="steps">
            <div className="step"><CoinsArt /><h3>1. Everyone saves together</h3><p className="muted" style={{ margin: 0 }}>Each month every member puts in the same amount.</p></div>
            <div className="step"><GavelArt /><h3>2. One member takes the pot</h3><p className="muted" style={{ margin: 0 }}>An auction decides who takes it this month, and the discount is shared back with the group.</p></div>
            <div className="step"><LampArt /><h3>3. Everyone gets a turn</h3><p className="muted" style={{ margin: 0 }}>By the last month every member has had the pot once.</p></div>
          </div>
        </div>
      </section>
      <section className="band">
        <div className="wrap">
          <h2>What you can do here</h2>
          <div className="trust">
            <div className="card"><span className="ic"><Icon name="rupee" /></span><h3>See every payment</h3><p className="muted" style={{ margin: 0 }}>What you owe, what is coming, and each payment our team has recorded for you.</p></div>
            <div className="card"><span className="ic"><Icon name="gavel" /></span><h3>Bid in auctions</h3><p className="muted" style={{ margin: 0 }}>Place a bid for your ticket and see where you stand. The system checks the limits for every bid.</p></div>
            <div className="card"><span className="ic"><Icon name="phone" /></span><h3>A real person to call</h3><p className="muted" style={{ margin: 0 }}>Your organiser's number is at the bottom of every page.</p></div>
          </div>
        </div>
      </section>
    </>
  );
}

// ---------------------------------------------------------------- signed in

const VIEWS: [string, string, string][] = [
  ['dashboard', 'Home', 'home'], ['tickets', 'My chits', 'ticket'], ['plans', 'Plans', 'gift'], ['payments', 'Payments', 'rupee'], ['auctions', 'Auctions', 'gavel'],
  ['approvals', 'Waiting on you', 'clock'], ['offers', 'Offers', 'gift'], ['more', 'More', 'more'],
];
const NAV = ['dashboard', 'tickets', 'plans', 'payments', 'auctions', 'approvals', 'offers'];

function viewFromHash(): string {
  const h = window.location.hash.replace(/^#\/?/, '').split('?')[0];
  return VIEWS.some(([k]) => k === h) ? h : 'dashboard';
}

function Portal({ member, site, onSignOut }: { member: Member; site: Site | null; onSignOut: () => void }) {
  const [view, setView] = useState(viewFromHash);
  const [menu, setMenu] = useState(false);
  const ov = useLoad<Overview>(api.dashboard);
  const first = useRef(true);
  useEffect(() => {
    const on = () => { setView(viewFromHash()); window.scrollTo({ top: 0 }); };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  const reload = ov.reload;
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    if (view === 'dashboard' || view === 'offers' || view === 'more') reload();
  }, [view, reload]);
  const go = useCallback((v: string) => { window.location.hash = '/' + v; }, []);
  const name = member.displayName ?? member.email;
  const firstName = name.split(/[ @]/)[0];
  const requests = ov.data?.summary.openRequests ?? 0;
  const offerCount = ov.data?.offers.length ?? 0;
  const badge = (k: string) => (k === 'approvals' && requests > 0 ? requests : k === 'more' && requests > 0 ? requests : 0);

  return (
    <div className="shell">
      <header className="top">
        <div className="wrap top-in">
          <a href="#/dashboard" aria-label="Honest Savers home"><Logo compact /></a>
          <nav className="nav" aria-label="Portal pages">
            {NAV.map(k => {
              const v = VIEWS.find(x => x[0] === k)!;
              return <a key={k} href={'#/' + k} aria-current={k === view ? 'page' : undefined}>{v[1]}{badge(k) > 0 && <span className="count">{badge(k)}</span>}</a>;
            })}
          </nav>
          <Who member={member} onSignOut={onSignOut} />
          <button className="burger" aria-label="Menu" aria-expanded={menu} onClick={() => setMenu(m => !m)}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
          </button>
        </div>
        {menu && (
          <div className="drawer">
            {NAV.map(k => {
              const v = VIEWS.find(x => x[0] === k)!;
              return <a key={k} href={'#/' + k} aria-current={k === view ? 'page' : undefined} onClick={() => setMenu(false)}>{v[1]}{badge(k) > 0 && <span className="count">{badge(k)}</span>}</a>;
            })}
          </div>
        )}
      </header>
      <main className="main">
        <div className="wrap">
          {view === 'dashboard' && <Dashboard ov={ov} go={go} firstName={firstName} />}
          {view === 'tickets' && <Tickets go={go} email={member.email} onSignOut={onSignOut} />}
          {view === 'plans' && <PlansPage initialJoin={new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('join') ?? undefined} />}
          {view === 'payments' && <Payments site={site} />}
          {view === 'auctions' && <Auctions />}
          {view === 'approvals' && <Approvals />}
          {view === 'offers' && <Offers ov={ov} />}
          {view === 'more' && <More name={name} email={member.email} site={site} count={{ requests, offers: offerCount }} go={go} onSignOut={onSignOut} />}
        </div>
      </main>
      <Footer site={site} />
    </div>
  );
}

export { NoAccount };
