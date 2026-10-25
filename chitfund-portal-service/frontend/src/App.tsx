import { useCallback, useEffect, useState } from 'react';
import { ApiError, Invitation, Member, Offer, PortalConfig, api } from './api';
import { dateOnly, dateTime, rupees } from './format';
import GoogleButton from './google';
import { Approvals, Auctions, Dashboard, OfferCards, Offers, Payments, Tickets, useLoad } from './member';

type Session = { state: 'loading' } | { state: 'out' } | { state: 'in'; member: Member };

const SIGN_IN_ERRORS: Record<string, string> = {
  INVALID_ID_TOKEN: 'Google sign-in could not be verified. Please try again.',
  GOOGLE_ACCOUNT_MISMATCH: 'This email address is already linked to a different Google account. Sign in with the Google account you used before.',
  MEMBER_BLOCKED: 'This account cannot sign in. Please contact the organiser.',
  TOO_MANY_REQUESTS: 'Too many attempts. Please wait a minute and try again.',
  SIGN_IN_NOT_CONFIGURED: 'Sign-in is not set up yet. Please contact the organiser.',
};

function inviteToken(path: string): string | null {
  const m = /^\/invite\/([A-Za-z0-9_-]{20,100})\/?$/.exec(path);
  return m ? m[1] : null;
}

export default function App() {
  const [config, setConfig] = useState<PortalConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [session, setSession] = useState<Session>({ state: 'loading' });
  const [signInError, setSignInError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const token = inviteToken(window.location.pathname);

  useEffect(() => {
    api.config().then(setConfig).catch(e => setConfigError(e instanceof Error ? e.message : String(e)));
    api.me()
      .then(member => setSession({ state: 'in', member }))
      .catch(() => setSession({ state: 'out' }));
  }, []);

  const onCredential = useCallback(async (idToken: string) => {
    setBusy(true);
    setSignInError(null);
    try {
      const member = await api.signIn(idToken);
      setSession({ state: 'in', member });
    } catch (e) {
      const code = e instanceof ApiError ? e.code : '';
      setSignInError(SIGN_IN_ERRORS[code] ?? (e instanceof Error ? e.message : 'Sign-in failed.'));
    } finally {
      setBusy(false);
    }
  }, []);

  async function signOut() {
    try {
      await api.signOut();
    } catch {
      /* the cookie may already be gone */
    }
    setSession({ state: 'out' });
    setSignInError(null);
  }

  return (
    <div className="shell">
      <header className="top">
        <a className="brand" href="/">Chit Fund Portal</a>
        {session.state === 'in' && (
          <div className="who">
            <span title={session.member.email}>{session.member.displayName ?? session.member.email}</span>
            <button className="link" onClick={signOut}>Sign out</button>
          </div>
        )}
      </header>
      <main className={session.state === 'in' && !token ? 'main main-wide' : 'main'}>
        {session.state === 'loading' && <p className="muted">Loading...</p>}
        {session.state === 'out' && (
          <>
            <SignInCard
              config={config}
              configError={configError}
              busy={busy}
              error={signInError}
              onCredential={onCredential}
              invited={token !== null}
            />
            {token === null && <PublicOffers />}
          </>
        )}
        {session.state === 'in' && token && <InvitePage token={token} member={session.member} onSignOut={signOut} />}
        {session.state === 'in' && !token && <Portal member={session.member} />}
      </main>
      <footer className="foot">Your sign-in is handled by Google. No password is stored here.</footer>
    </div>
  );
}

function SignInCard(props: {
  config: PortalConfig | null;
  configError: string | null;
  busy: boolean;
  error: string | null;
  onCredential: (t: string) => void;
  invited: boolean;
}) {
  const { config } = props;
  return (
    <section className="card narrow">
      <h1>{props.invited ? 'You have been invited to a chit group' : 'Sign in'}</h1>
      <p className="muted">
        {props.invited
          ? 'Sign in with the Google account for the email address the invitation was sent to. You can then review the group and reply.'
          : 'Sign in with your Google account to see your tickets, payments and auctions.'}
      </p>
      {props.configError && <p className="error">{props.configError}</p>}
      {config && !config.googleClientId && <p className="error">Sign-in is not set up yet. Please contact the organiser.</p>}
      {config && config.googleClientId && <GoogleButton clientId={config.googleClientId} onCredential={props.onCredential} />}
      {!config && !props.configError && <p className="muted">Loading...</p>}
      {props.busy && <p className="muted">Signing you in...</p>}
      {props.error && <p className="error" role="alert">{props.error}</p>}
    </section>
  );
}

/** What a visitor sees before signing in: the offers staff marked public. Failing quietly is right here; the sign-in card is the point. */
function PublicOffers() {
  const s = useLoad<Offer[]>(api.publicOffers);
  if (!s.data || s.data.length === 0) return null;
  return (
    <section className="public-offers">
      <h2>Current offers</h2>
      <OfferCards offers={s.data} />
    </section>
  );
}

const VIEWS: [string, string][] = [
  ['dashboard', 'Dashboard'], ['tickets', 'My tickets'], ['payments', 'Payments'], ['auctions', 'Auctions'], ['approvals', 'Approvals'], ['offers', 'Offers'],
];

function viewFromHash(): string {
  const h = window.location.hash.replace(/^#\/?/, '');
  return VIEWS.some(([k]) => k === h) ? h : 'dashboard';
}

/** The signed-in member's pages. The address after # picks the page, so there is nothing for the server to route. */
function Portal({ member }: { member: Member }) {
  const [view, setView] = useState(viewFromHash);
  useEffect(() => {
    const on = () => setView(viewFromHash());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  const go = useCallback((v: string) => { window.location.hash = '/' + v; }, []);
  return (
    <>
      <h1 className="hello">Hello{member.displayName ? `, ${member.displayName.split(' ')[0]}` : ''}</h1>
      <nav className="tabs" aria-label="Portal pages">
        {VIEWS.map(([k, text]) => (
          <a key={k} href={'#/' + k} className={k === view ? 'tab tab-on' : 'tab'} aria-current={k === view ? 'page' : undefined}>{text}</a>
        ))}
      </nav>
      {view === 'dashboard' && <Dashboard go={go} />}
      {view === 'tickets' && <Tickets />}
      {view === 'payments' && <Payments />}
      {view === 'auctions' && <Auctions />}
      {view === 'approvals' && <Approvals />}
      {view === 'offers' && <Offers />}
    </>
  );
}

const STATUS_TEXT: Record<string, string> = {
  PENDING: 'Waiting for your reply',
  ACCEPTED_AWAITING_PAYMENT: 'Accepted, waiting for payment',
  ACCEPTED: 'Accepted',
  DECLINED: 'You declined this invitation',
  EXPIRED: 'Expired',
  REVOKED: 'Withdrawn by the organiser',
};

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
    return () => {
      live = false;
    };
  }, [token]);

  async function accept() {
    setBusy(true);
    setActionError(null);
    try {
      setInv(await api.accept(token));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function decline() {
    setBusy(true);
    setActionError(null);
    try {
      setInv(await api.decline(token));
      setConfirming(false);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (error) {
    if (error.code === 'INVITE_EMAIL_MISMATCH') {
      return (
        <section className="card narrow">
          <h1>This invitation is for a different email address</h1>
          <p>You are signed in as <strong>{member.email}</strong>, but the invitation was sent to another address.</p>
          <p className="muted">Sign out and sign in with the Google account for the address the invitation was sent to.</p>
          <button className="btn" onClick={onSignOut}>Sign out</button>
        </section>
      );
    }
    if (error.code === 'INVITE_NOT_AVAILABLE') {
      return (
        <section className="card narrow">
          <h1>This invitation link is not valid any more</h1>
          <p className="muted">It may have expired or been replaced. Ask the organiser to send it again.</p>
        </section>
      );
    }
    if (error.code === 'NOT_SIGNED_IN') {
      return (
        <section className="card narrow">
          <h1>Your session has ended</h1>
          <p><a href={window.location.pathname}>Reload this page</a> and sign in again.</p>
        </section>
      );
    }
    return (
      <section className="card narrow">
        <h1>Something went wrong</h1>
        <p className="error">{error.message}</p>
      </section>
    );
  }
  if (!inv) return <p className="muted">Loading your invitation...</p>;

  const g = inv.group;
  return (
    <section className="card">
      <p className="eyebrow">Invitation</p>
      <h1>{inv.groupName}</h1>
      <p className={`status status-${inv.status.toLowerCase()}`}>{STATUS_TEXT[inv.status] ?? inv.status}</p>
      <dl className="facts">
        <div><dt>Tickets offered to you</dt><dd>{inv.tickets}</dd></div>
        {g && <div><dt>Chit value</dt><dd>{rupees(g.chitValue)}</dd></div>}
        {g && <div><dt>Monthly subscription per ticket</dt><dd>{rupees(g.subscription)}</dd></div>}
        {g && <div><dt>Tickets in the group</dt><dd>{g.tickets}</dd></div>}
        {g && <div><dt>Number of cycles</dt><dd>{g.cycles}</dd></div>}
        {g && <div><dt>Starts</dt><dd>{dateOnly(g.startDate)}</dd></div>}
        {g && g.seatsLeft !== undefined && <div><dt>Seats left</dt><dd>{g.seatsLeft}</dd></div>}
        <div><dt>Reply by</dt><dd>{dateTime(inv.expiresAt)}</dd></div>
      </dl>
      {inv.groupUnavailable && <p className="muted">The group details could not be loaded right now. Reload the page in a moment.</p>}
      {actionError && <p className="error" role="alert">{actionError}</p>}
      {inv.cashDuePerTicket !== undefined && inv.cashDuePerTicket > 0 && (inv.canAccept || inv.status === 'ACCEPTED_AWAITING_PAYMENT') && (
        <p className="small">
          To take your {inv.tickets === 1 ? 'ticket' : `${inv.tickets} tickets`} you will pay <strong>{rupees(inv.cashDuePerTicket)}</strong> per ticket
          at enrolment. The organiser records your payment and then enrols you.
        </p>
      )}
      {inv.status === 'ACCEPTED_AWAITING_PAYMENT' && (
        <p>Thank you for accepting. Your seat is confirmed once the organiser has recorded your payment; you do not need to do anything else here.</p>
      )}
      {inv.status === 'ACCEPTED' && (
        <p>
          You are enrolled{inv.ticketNos && inv.ticketNos.length > 0 ? <> with {inv.ticketNos.length === 1 ? 'ticket' : 'tickets'} <strong>{inv.ticketNos.join(', ')}</strong></> : null}.
          {inv.ticketsEnrolled < inv.tickets && <> {inv.tickets - inv.ticketsEnrolled} more of the {inv.tickets} offered will be added by the organiser.</>}
        </p>
      )}
      {inv.canDecline && !confirming && (
        <div className="actions">
          <button className="btn" onClick={accept} disabled={busy || !inv.canAccept}>{busy ? 'Joining...' : 'Accept and join'}</button>
          <button className="btn btn-quiet" onClick={() => setConfirming(true)} disabled={busy}>Decline</button>
        </div>
      )}
      {inv.canDecline && confirming && (
        <div className="actions">
          <span>Decline this invitation?</span>
          <button className="btn btn-danger" onClick={decline} disabled={busy}>{busy ? 'Declining...' : 'Yes, decline'}</button>
          <button className="btn btn-quiet" onClick={() => setConfirming(false)} disabled={busy}>Cancel</button>
        </div>
      )}
    </section>
  );
}
