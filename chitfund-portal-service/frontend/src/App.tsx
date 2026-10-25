import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, Invitation, Member, Offer, PortalConfig, Site, api } from './api';
import { BRAND, CoinsArt, GavelArt, HeroArt, Icon, LampArt, Logo, PassbookArt } from './brand';
import { dateOnly, dateTime, rupees } from './format';
import GoogleButton from './google';
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

function inviteToken(path: string): string | null {
  const m = /^\/invite\/([A-Za-z0-9_-]{20,100})\/?$/.exec(path);
  return m ? m[1] : null;
}

const telHref = (p: string) => 'tel:' + p.replace(/[^+\d]/g, '');

export default function App() {
  const [config, setConfig] = useState<PortalConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [site, setSite] = useState<Site | null>(null);
  const [session, setSession] = useState<Session>({ state: 'loading' });
  const [signInError, setSignInError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const token = inviteToken(window.location.pathname);

  useEffect(() => {
    api.config().then(setConfig).catch(e => setConfigError(e instanceof Error ? e.message : String(e)));
    api.site().then(setSite).catch(() => setSite(null));
    api.me().then(member => setSession({ state: 'in', member })).catch(() => setSession({ state: 'out' }));
  }, []);

  const onCredential = useCallback(async (idToken: string) => {
    setBusy(true);
    setSignInError(null);
    try {
      setSession({ state: 'in', member: await api.signIn(idToken) });
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
    return (
      <div className="shell">
        <Landing config={config} configError={configError} busy={busy} error={signInError} onCredential={onCredential} invited={token !== null} />
        <Footer site={site} />
      </div>
    );
  }

  const member = session.member;
  if (token) {
    return (
      <div className="shell">
        <header className="top"><div className="wrap top-in"><a href="/" aria-label="Honest Savers home"><Logo compact /></a><Who member={member} onSignOut={signOut} /></div></header>
        <main className="main"><div className="wrap"><InvitePage token={token} member={member} onSignOut={signOut} /></div></main>
        <Footer site={site} />
      </div>
    );
  }
  return <Portal member={member} site={site} onSignOut={signOut} />;
}

function Who({ member, onSignOut }: { member: Member; onSignOut: () => void }) {
  const name = member.displayName ?? member.email;
  return (
    <div className="who">
      <span className="avatar" title={member.email}>{name.charAt(0).toUpperCase()}</span>
      <span className="who-name">{name}</span>
      <button className="link small" onClick={onSignOut}>Sign out</button>
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
          <p>Your sign-in is handled by Google. No password is stored here.</p>
        </div>
      </div>
    </footer>
  );
}

// ---------------------------------------------------------------- signed out

function Landing(props: { config: PortalConfig | null; configError: string | null; busy: boolean; error: string | null; onCredential: (t: string) => void; invited: boolean }) {
  const { config } = props;
  const signIn = (
    <div className="signin-box">
      <strong>{props.invited ? 'Sign in to see your invitation' : 'Sign in to your account'}</strong>
      {props.configError && <p className="error" style={{ margin: 0 }}>{props.configError}</p>}
      {config && !config.googleClientId && <p className="error" style={{ margin: 0 }}>Sign-in is not set up yet. Please contact your organiser.</p>}
      {config && config.googleClientId && <GoogleButton clientId={config.googleClientId} onCredential={props.onCredential} />}
      {!config && !props.configError && <p className="muted" style={{ margin: 0 }}>Loading...</p>}
      {props.busy && <p className="muted" style={{ margin: 0 }} role="status">Signing you in...</p>}
      {props.error && <p className="error" role="alert" style={{ margin: 0 }}>{props.error}</p>}
      <span className="small muted">Use the Google account for the e-mail address your organiser has for you.</span>
    </div>
  );
  return (
    <>
      <header className="land-top"><div className="wrap top-in"><Logo /></div></header>
      <main>
        <div className="wrap hero">
          <div>
            <h1>Honest dealings.<br /><span>Happy savers.</span></h1>
            <p className="lead">
              {props.invited
                ? 'You have been invited to join a chit group. Sign in with Google to see the details and reply.'
                : 'See your chits, your payments and your auctions in one place.'}
            </p>
            {signIn}
          </div>
          <HeroArt />
        </div>
        {!props.invited && <PublicOffers />}
        {!props.invited && <HowItWorks />}
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
  ['dashboard', 'Home', 'home'], ['tickets', 'My chits', 'ticket'], ['payments', 'Payments', 'rupee'], ['auctions', 'Auctions', 'gavel'],
  ['approvals', 'Waiting on you', 'clock'], ['offers', 'Offers', 'gift'], ['more', 'More', 'more'],
];
const TABS = ['dashboard', 'tickets', 'payments', 'auctions', 'more'];
const NAV = ['dashboard', 'tickets', 'payments', 'auctions', 'approvals', 'offers'];

function viewFromHash(): string {
  const h = window.location.hash.replace(/^#\/?/, '');
  return VIEWS.some(([k]) => k === h) ? h : 'dashboard';
}

function Portal({ member, site, onSignOut }: { member: Member; site: Site | null; onSignOut: () => void }) {
  const [view, setView] = useState(viewFromHash);
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
        </div>
      </header>
      <main className="main">
        <div className="wrap">
          {view === 'dashboard' && <Dashboard ov={ov} go={go} firstName={firstName} />}
          {view === 'tickets' && <Tickets go={go} email={member.email} onSignOut={onSignOut} />}
          {view === 'payments' && <Payments site={site} />}
          {view === 'auctions' && <Auctions />}
          {view === 'approvals' && <Approvals />}
          {view === 'offers' && <Offers ov={ov} />}
          {view === 'more' && <More name={name} email={member.email} site={site} count={{ requests, offers: offerCount }} go={go} onSignOut={onSignOut} />}
        </div>
      </main>
      <Footer site={site} />
      <nav className="tabbar" aria-label="Main pages">
        {TABS.map(k => {
          const v = VIEWS.find(x => x[0] === k)!;
          const active = k === view || (k === 'more' && (view === 'approvals' || view === 'offers'));
          return (
            <a key={k} href={'#/' + k} aria-current={active ? 'page' : undefined}>
              <span className="ti"><Icon name={v[2]} size={22} /></span>{v[1]}
              {badge(k) > 0 && <span className="count">{badge(k)}</span>}
            </a>
          );
        })}
      </nav>
    </div>
  );
}

// ---------------------------------------------------------------- invitation

const STATUS: Record<string, [string, string]> = {
  PENDING: ['Waiting for your reply', 'badge-warn'],
  ACCEPTED_AWAITING_PAYMENT: ['Accepted, waiting for payment', 'badge-info'],
  ACCEPTED: ['Accepted', 'badge-good'],
  DECLINED: ['You declined this invitation', 'badge-neutral'],
  EXPIRED: ['Expired', 'badge-neutral'],
  REVOKED: ['Withdrawn by the organiser', 'badge-neutral'],
};

function Notice({ title, children, art }: { title: string; children: React.ReactNode; art?: React.ReactNode }) {
  return <section className="card narrow">{art ?? <PassbookArt />}<h1 style={{ margin: '14px 0 8px', fontSize: 26 }}>{title}</h1>{children}</section>;
}

function InvitePage({ token, member, onSignOut }: { token: string; member: Member; onSignOut: () => void }) {
  const [inv, setInv] = useState<Invitation | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    api.invitation(token)
      .then(i => live && setInv(i))
      .catch(e => live && setError(e instanceof ApiError ? e : new ApiError(0, 'ERROR', String(e))));
    return () => { live = false; };
  }, [token]);

  async function run(call: () => Promise<Invitation>, after?: () => void) {
    setBusy(true);
    setActionError(null);
    try { setInv(await call()); after?.(); } catch (e) { setActionError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  }

  if (error) {
    if (error.code === 'INVITE_EMAIL_MISMATCH') {
      return (
        <Notice title="This invitation is for a different e-mail address">
          <p>You are signed in as <strong>{member.email}</strong>, but the invitation was sent to another address.</p>
          <p className="muted">Sign out and sign in with the Google account for the address the invitation was sent to.</p>
          <button className="btn" onClick={onSignOut}>Sign out</button>
        </Notice>
      );
    }
    if (error.code === 'INVITE_NOT_AVAILABLE') {
      return <Notice title="This invitation link is not valid any more"><p className="muted">It may have expired or been replaced. Please ask your organiser to send it again.</p></Notice>;
    }
    if (error.code === 'NOT_SIGNED_IN') {
      return <Notice title="Your session has ended"><p>For your safety we signed you out. <a href={window.location.pathname}>Reload this page</a> and sign in again.</p></Notice>;
    }
    return <Notice title="Something went wrong"><p className="error">{error.message}</p></Notice>;
  }
  if (!inv) return <p className="muted" role="status">Loading your invitation...</p>;

  const g = inv.group;
  const [statusText, statusClass] = STATUS[inv.status] ?? [inv.status, 'badge-neutral'];
  return (
    <section className="card narrow">
      <PassbookArt />
      <p className="muted" style={{ margin: '14px 0 4px' }}>You are invited to join</p>
      <h1>{inv.groupName}</h1>
      <p><span className={`badge ${statusClass}`}>{statusText}</span></p>
      <dl className="facts">
        <div><dt>Tickets offered to you</dt><dd>{inv.tickets}</dd></div>
        {g && <div><dt>Chit value</dt><dd>{rupees(g.chitValue)}</dd></div>}
        {g && <div><dt>Monthly subscription per ticket</dt><dd>{rupees(g.subscription)}</dd></div>}
        {g && <div><dt>Tickets in the group</dt><dd>{g.tickets}</dd></div>}
        {g && <div><dt>Number of instalments</dt><dd>{g.cycles}</dd></div>}
        {g && <div><dt>Starts</dt><dd>{dateOnly(g.startDate)}</dd></div>}
        {g && g.seatsLeft !== undefined && <div><dt>Seats left</dt><dd>{g.seatsLeft}</dd></div>}
        <div><dt>Reply by</dt><dd>{dateTime(inv.expiresAt)}</dd></div>
      </dl>
      {inv.groupUnavailable && <p className="muted">The group details could not be loaded right now. Please reload the page in a moment.</p>}
      {actionError && <p className="error" role="alert">{actionError}</p>}
      {inv.cashDuePerTicket !== undefined && inv.cashDuePerTicket > 0 && (inv.canAccept || inv.status === 'ACCEPTED_AWAITING_PAYMENT') && (
        <p className="note">
          To take your {inv.tickets === 1 ? 'ticket' : `${inv.tickets} tickets`} you will pay <strong>{rupees(inv.cashDuePerTicket)}</strong> per ticket when you join.
          Our team records your payment and then confirms your seat.
        </p>
      )}
      {inv.status === 'ACCEPTED_AWAITING_PAYMENT' && <p>Thank you for accepting. Your seat is confirmed once our team has recorded your payment. You do not need to do anything else here.</p>}
      {inv.status === 'ACCEPTED' && (
        <p>
          You are enrolled{inv.ticketNos && inv.ticketNos.length > 0 ? <> with {inv.ticketNos.length === 1 ? 'ticket' : 'tickets'} <strong>{inv.ticketNos.join(', ')}</strong></> : null}.
          {inv.ticketsEnrolled < inv.tickets && <> {inv.tickets - inv.ticketsEnrolled} more of the {inv.tickets} offered will be added by our team.</>}
          {' '}<a href="/">Go to my home page</a>
        </p>
      )}
      {inv.canDecline && !confirming && (
        <div className="actions">
          <button className="btn" onClick={() => run(() => api.accept(token))} disabled={busy || !inv.canAccept}>{busy ? 'Joining...' : 'Accept and join'}</button>
          <button className="btn btn-ghost" onClick={() => setConfirming(true)} disabled={busy}>Decline</button>
        </div>
      )}
      {inv.canDecline && confirming && (
        <div className="sheet">
          <strong>Decline this invitation?</strong>
          <div className="actions" style={{ marginTop: 10 }}>
            <button className="btn btn-danger" onClick={() => run(() => api.decline(token), () => setConfirming(false))} disabled={busy}>{busy ? 'Declining...' : 'Yes, decline'}</button>
            <button className="btn btn-ghost" onClick={() => setConfirming(false)} disabled={busy}>Cancel</button>
          </div>
        </div>
      )}
    </section>
  );
}

export { NoAccount };
