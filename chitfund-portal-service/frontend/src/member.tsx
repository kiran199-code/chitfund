import { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, Auction, Due, MemberRequest, Offer, Overview, PaymentsPage, Site, Ticket, api } from './api';
import { CoinsArt, ConfettiArt, GavelArt, GroupCover, HeroArt, Icon, LampArt, PassbookArt, RelaxArt } from './brand';
import { dateOnly, dateTime, newKey, rupees, rupeesShort, safeUrl, titleCase } from './format';

// ---------------------------------------------------------------- loading helpers

export interface Loaded<T> { data: T | null; error: string | null; loading: boolean; reload: () => void; set: (v: T) => void }

/** Loads once, again on reload(); optionally polls while the tab is visible. */
export function useLoad<T>(fn: () => Promise<T>, pollMs?: (d: T | null) => number | null): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const dataRef = useRef<T | null>(null);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  useEffect(() => {
    let live = true;
    let timer: number | undefined;
    const run = (first: boolean) => {
      if (first) setLoading(true);
      fnRef.current().then(d => {
        if (!live) return;
        dataRef.current = d; setData(d); setError(null); setLoading(false);
      }).catch(e => {
        if (!live) return;
        setError(e instanceof Error ? e.message : String(e)); setLoading(false);
      }).finally(() => {
        if (!live) return;
        const ms = pollMs ? pollMs(dataRef.current) : null;
        if (ms) timer = window.setTimeout(() => { if (document.visibilityState === 'visible') run(false); else schedule(ms); }, ms);
      });
    };
    const schedule = (ms: number) => { timer = window.setTimeout(() => run(false), ms); };
    run(true);
    return () => { live = false; if (timer) window.clearTimeout(timer); };
  }, [tick]); // eslint-disable-line react-hooks/exhaustive-deps

  return { data, error, loading, reload: useCallback(() => setTick(t => t + 1), []), set: (v: T) => { dataRef.current = v; setData(v); } };
}

function State<T>({ s, what, children }: { s: Loaded<T>; what: string; children: (d: T) => ReactNode }) {
  if (s.data) return <>{children(s.data)}</>;
  if (s.loading) return <p className="muted" role="status">Loading {what}...</p>;
  return (
    <div className="card">
      <p className="error" role="alert">{s.error ?? `Could not load ${what}.`}</p>
      <button className="btn btn-ghost btn-sm" onClick={s.reload}>Try again</button>
    </div>
  );
}

function Empty({ art, children }: { art?: ReactNode; children: ReactNode }) {
  return <div className="empty">{art}<span>{children}</span></div>;
}

function Badge({ tone, children }: { tone: 'good' | 'warn' | 'bad' | 'info' | 'neutral'; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

function PageHead({ title, sub }: { title: string; sub?: string }) {
  return <div className="page-head"><h1>{title}</h1>{sub && <p>{sub}</p>}</div>;
}

export function NoAccount({ email, onSignOut }: { email?: string; onSignOut?: () => void }) {
  return (
    <section className="card narrow">
      <PassbookArt />
      <h1 style={{ marginTop: 14 }}>Your passbook is empty for now</h1>
      <p>This Google account does not have any chit tickets yet.</p>
      <p className="muted">
        Choose a plan and send a request. Once our team confirms your seat, your chit shows up here{email ? <> under <strong>{email}</strong></> : ''}.
      </p>
      <a className="btn" href="#/plans" style={{ marginBottom: 10 }}>Browse plans</a>{' '}
      {onSignOut && <button className="btn btn-ghost" onClick={onSignOut}>Sign out and use another account</button>}
    </section>
  );
}

// ---------------------------------------------------------------- shared rows

function DueRow({ d }: { d: Due }) {
  const overdue = d.view === 'OVERDUE' || d.daysOverdue > 0;
  return (
    <li className="row">
      <div>
        <strong>{d.groupName}</strong> <span className="muted">ticket {d.ticketNo}, instalment {d.cycleNo}</span>
        <div className="small muted">
          {overdue ? `${d.daysOverdue} day${d.daysOverdue === 1 ? '' : 's'} late, was due ${dateOnly(d.dueDate)}` : `Due ${dateOnly(d.dueDate)}`}
          {d.lateFeeOutstanding > 0 ? `. Includes late fee ${rupeesShort(d.lateFeeOutstanding)}` : ''}
        </div>
      </div>
      <div className="amount">
        {rupeesShort(d.totalOutstanding)}
        <div>{overdue ? <Badge tone="bad">Overdue</Badge> : d.view === 'UPCOMING' ? <Badge tone="info">Upcoming</Badge> : <Badge tone="warn">Due</Badge>}</div>
      </div>
    </li>
  );
}

const REQUEST_TEXT: Record<string, (r: MemberRequest) => string> = {
  TRANSFER: r => (r.role === 'INCOMING' ? `Ticket ${r.ticketNo} is being transferred to you` : `Your ticket ${r.ticketNo} is being transferred to someone else`),
  PRIZE_PAYOUT: r => `Prize payout for your ticket ${r.ticketNo}${r.cycleNo ? `, instalment ${r.cycleNo}` : ''}`,
};

function RequestRow({ r }: { r: MemberRequest }) {
  const text = (REQUEST_TEXT[r.kind] ?? (() => titleCase(r.kind)))(r);
  return (
    <li className="row">
      <div className="row-main">
        <span className="avatar" aria-hidden="true"><Icon name={r.kind === 'PRIZE_PAYOUT' ? 'rupee' : 'ticket'} /></span>
        <div>
          <strong>{text}</strong>
          <div className="small muted">{r.groupName}{r.requestedAt ? `, requested ${dateOnly(r.requestedAt)}` : ''}{r.amount ? `, ${rupeesShort(r.amount)}` : ''}</div>
        </div>
      </div>
      <Badge tone={r.inProgress ? 'warn' : r.status === 'REJECTED' || r.status === 'CANCELLED' ? 'neutral' : 'good'}>{r.inProgress ? 'Pending' : titleCase(r.status)}</Badge>
    </li>
  );
}

export function OfferCards({ offers, feature }: { offers: Offer[]; feature?: boolean }) {
  if (offers.length === 0) return null;
  return (
    <div className="offers">
      {offers.map((o, i) => {
        const link = safeUrl(o.linkUrl);
        const img = safeUrl(o.imageUrl);
        return (
          <article className={feature && i === 0 ? 'offer offer-feature' : 'offer'} key={o.id}>
            <div className="offer-pic">{img ? <img src={img} alt="" loading="lazy" referrerPolicy="no-referrer" /> : <span>{o.title}</span>}</div>
            <div className="offer-body">
              <Badge tone="info">{o.endsOn ? `Until ${dateOnly(o.endsOn)}` : `From ${dateOnly(o.startsOn)}`}</Badge>
              <h3>{o.title}</h3>
              {o.body && <p>{o.body}</p>}
              {link && <a className="btn btn-ghost btn-sm" href={link} target="_blank" rel="noopener noreferrer">Learn more</a>}
            </div>
          </article>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------- Home

function summaryLine(o: Overview): ReactNode {
  const s = o.summary;
  const parts: ReactNode[] = [];
  parts.push(<span key="c">You have <strong>{s.ticketsHeld} chit{s.ticketsHeld === 1 ? '' : 's'}</strong>.</span>);
  if (s.overdueCount > 0) {
    const late = Math.max(0, ...o.nextPayments.map(d => d.daysOverdue));
    parts.push(<span key="o"> {s.overdueCount === 1 ? 'One payment is' : `${s.overdueCount} payments are`} <strong>overdue{late > 0 ? `, ${late} day${late === 1 ? '' : 's'} late` : ''}</strong>.</span>);
  } else if (s.nextPayment) {
    parts.push(<span key="n"> You are all paid up. Next payment <strong>{rupeesShort(s.nextPayment.totalOutstanding)}</strong> on {dateOnly(s.nextPayment.dueDate)}.</span>);
  } else {
    parts.push(<span key="n"> You are all paid up.</span>);
  }
  if (s.liveAuctions > 0) parts.push(<span key="a"> An <strong>auction is open now</strong>.</span>);
  return <>{parts}</>;
}

function Tile({ icon, k, v, sub, tone, onClick }: { icon: string; k: string; v: ReactNode; sub: ReactNode; tone?: 'bad' | 'good'; onClick: () => void }) {
  return (
    <button className={tone ? `tile tile-${tone}` : 'tile'} onClick={onClick}>
      <span className="ic"><Icon name={icon} /></span>
      <span className="k">{k}</span><strong>{v}</strong><em>{sub}</em>
    </button>
  );
}

export function Dashboard({ ov, go, firstName }: { ov: Loaded<Overview>; go: (view: string) => void; firstName: string }) {
  return (
    <State s={ov} what="your home page">
      {o => {
        if (!o.customer) {
          return (
            <>
              <NoAccount />
              {o.offers.length > 0 && <><h2 style={{ margin: '24px 0 12px' }}>Offers for you</h2><OfferCards offers={o.offers} /></>}
            </>
          );
        }
        const sum = o.summary;
        const live = o.auctions.find(a => a.state === 'LIVE');
        return (
          <>
            {o.unavailable.length > 0 && <p className="banner" role="status">Some information could not be loaded right now ({o.unavailable.join(', ')}). Please reload in a moment.</p>}
            <div className="hello">
              <div className="hello-card">
                <h1>Hello, {firstName}</h1>
                <p>{summaryLine(o)}</p>
                <div className="actions">
                  <button className="btn btn-gold" onClick={() => go('payments')}>{sum.overdueCount > 0 ? 'Pay or see what is due' : 'See my payments'}</button>
                  {sum.liveAuctions > 0 && <button className="btn btn-ghost" style={{ color: '#fff', borderColor: '#fff' }} onClick={() => go('auctions')}>Go to the live auction</button>}
                </div>
              </div>
              <div className="hello-art"><HeroArt /></div>
            </div>

            <div className="tiles">
              <Tile icon="ticket" k="My chits" v={sum.ticketsHeld} sub="tickets" onClick={() => go('tickets')} />
              <Tile icon="rupee" k="Next payment" v={sum.nextPayment ? rupeesShort(sum.nextPayment.totalOutstanding) : 'None'} sub={sum.nextPayment ? `due ${dateOnly(sum.nextPayment.dueDate)}` : 'nothing due'} onClick={() => go('payments')} />
              <Tile icon="alert" k="Overdue" v={sum.overdueCount > 0 ? rupeesShort(sum.overdueTotal) : 'None'} tone={sum.overdueCount > 0 ? 'bad' : undefined}
                sub={sum.overdueCount > 0 ? `${sum.overdueCount} instalment${sum.overdueCount === 1 ? '' : 's'}` : 'all paid up'} onClick={() => go('payments')} />
              <Tile icon="gavel" k="Auctions" v={`${sum.liveAuctions} live`} tone={sum.liveAuctions > 0 ? 'good' : undefined} sub={`${sum.upcomingAuctions} upcoming`} onClick={() => go('auctions')} />
              <Tile icon="clock" k="Waiting on you" v={sum.openRequests} sub="in progress" onClick={() => go('approvals')} />
            </div>

            <div className="grid2" style={{ marginBottom: 20 }}>
              <section className="card">
                <div className="card-head"><h2>Payments to make</h2><button className="link" onClick={() => go('payments')}>See all</button></div>
                {o.nextPayments.length === 0 ? <Empty art={<RelaxArt />}>Nothing is due right now.</Empty> : <ul className="rows">{o.nextPayments.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
              </section>
              <section className={live ? 'card card-live' : 'card'}>
                <div className="card-head"><h2>{live ? 'Auction open now' : 'Auctions'}</h2><button className="link" onClick={() => go('auctions')}>See all</button></div>
                {o.auctions.length === 0 ? <Empty art={<GavelArt />}>No live or upcoming auctions.</Empty> : (
                  <>
                    {live && (
                      <div className="live-card">
                        <GavelArt />
                        <div>
                          <strong style={{ fontSize: 18 }}>{live.groupName}, instalment {live.cycleNo}</strong>
                          <div className="small muted">{live.bidsHidden ? 'Bids are sealed until it closes' : live.highestBid ? `Best bid so far ${rupeesShort(live.highestBid)}` : 'No bids yet'}</div>
                          {live.myBids.length > 0 && <div className="small muted">Your best bid: {rupeesShort(Math.max(...live.myBids.map(b => b.discountAmount)))}</div>}
                          <button className="btn btn-sm" style={{ marginTop: 8 }} onClick={() => go('auctions')}>Bid now</button>
                        </div>
                      </div>
                    )}
                    <ul className="rows">
                      {o.auctions.filter(a => a !== live).map((a, i) => (
                        <li className="row" key={i}>
                          <div><strong>{a.groupName}</strong> <span className="muted">instalment {a.cycleNo}</span><div className="small muted">{a.state === 'LIVE' ? 'Bidding is open now' : `Auction on ${dateOnly(a.auctionDate)}`}</div></div>
                          <Badge tone={a.state === 'LIVE' ? 'good' : 'info'}>{a.state === 'LIVE' ? 'Live' : 'Upcoming'}</Badge>
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </section>
            </div>

            <div className="grid2" style={{ marginBottom: 20 }}>
              <section className="card">
                <div className="card-head"><h2>Waiting on you</h2><button className="link" onClick={() => go('approvals')}>See all</button></div>
                {o.requests.length === 0 ? <Empty art={<RelaxArt />}>Nothing is waiting on you.</Empty> : <ul className="rows">{o.requests.map((r, i) => <RequestRow key={i} r={r} />)}</ul>}
              </section>
              <section className="card">
                <div className="card-head"><h2>My chits at a glance</h2><button className="link" onClick={() => go('tickets')}>Open</button></div>
                {o.tickets.map(t => (
                  <div className="progress-row" key={`${t.groupCode}-${t.ticketNo}`}>
                    <div><strong>{t.groupName}</strong> <span className="muted">ticket {t.ticketNo}</span><div className="small muted">{t.cyclesPaid} of {t.cycleCount} paid</div></div>
                    <div className="bar" role="img" aria-label={`${t.cyclesPaid} of ${t.cycleCount} instalments paid`}><span style={{ width: `${t.cycleCount > 0 ? Math.min(100, (t.cyclesPaid / t.cycleCount) * 100) : 0}%` }} /></div>
                  </div>
                ))}
              </section>
            </div>

            {o.offers.length > 0 && (
              <section>
                <div className="card-head"><h2>Offers for you</h2><button className="link" onClick={() => go('offers')}>See all</button></div>
                <OfferCards offers={o.offers.slice(0, 3)} />
              </section>
            )}
          </>
        );
      }}
    </State>
  );
}

// ---------------------------------------------------------------- My chits

function TicketCard({ t, go }: { t: Ticket; go: (v: string) => void }) {
  const pct = t.cycleCount > 0 ? Math.min(100, Math.round((t.cyclesPaid / t.cycleCount) * 100)) : 0;
  return (
    <section className="card ticket">
      <GroupCover code={t.groupCode} />
      <div className="ticket-body">
        <div className="card-head" style={{ marginBottom: 0 }}>
          <h2>{t.groupName}</h2>
          <Badge tone={t.ticketStatus === 'ACTIVE' ? 'good' : 'neutral'}>{titleCase(t.ticketStatus)}</Badge>
        </div>
        <div className="small muted">Ticket {t.ticketNo}. Started {dateOnly(t.startDate)}. {titleCase(t.frequency)}.</div>
        <strong>{t.cyclesPaid} of {t.cycleCount} instalments paid</strong>
        <div className="bar" role="img" aria-label={`${pct} percent of instalments paid`}><span style={{ width: `${pct}%` }} /></div>
        <dl className="facts">
          <div><dt>Chit value</dt><dd>{rupeesShort(t.chitValue)}</dd></div>
          <div><dt>{titleCase(t.frequency)} subscription</dt><dd>{rupeesShort(t.subscription)}</dd></div>
          <div><dt>Prize</dt><dd>{t.prizeWon ? `Won${t.prizedCycleNo ? ` in instalment ${t.prizedCycleNo}` : ''}` : 'Not won yet'}</dd></div>
          <div><dt>Dividends received</dt><dd>{rupeesShort(t.dividendsReceived)}</dd></div>
        </dl>
        <div className="actions">
          <button className="btn btn-ghost btn-sm" onClick={() => go('payments')}>See payments</button>
          <button className="btn btn-ghost btn-sm" onClick={() => go('auctions')}>See auctions</button>
        </div>
      </div>
    </section>
  );
}

export function Tickets({ go, email, onSignOut }: { go: (v: string) => void; email?: string; onSignOut?: () => void }) {
  const s = useLoad<Ticket[]>(api.tickets);
  const [group, setGroup] = useState<string>('');
  return (
    <>
      <PageHead title="My chits" sub="Everything you have joined, and how far along each one is." />
      <State s={s} what="your chits">
        {rows => {
          if (rows.length === 0) return <NoAccount email={email} onSignOut={onSignOut} />;
          const groups = Array.from(new Map(rows.map(t => [t.groupCode, t.groupName])).entries());
          const shown = group ? rows.filter(t => t.groupCode === group) : rows;
          return (
            <>
              {groups.length > 1 && (
                <div className="chips" role="group" aria-label="Filter by group">
                  <button className="chip" aria-pressed={group === ''} onClick={() => setGroup('')}>All groups</button>
                  {groups.map(([code, name]) => <button key={code} className="chip" aria-pressed={group === code} onClick={() => setGroup(code)}>{name}</button>)}
                </div>
              )}
              <div className="grid2">{shown.map(t => <TicketCard key={`${t.groupCode}-${t.ticketNo}`} t={t} go={go} />)}</div>
            </>
          );
        }}
      </State>
    </>
  );
}

// ---------------------------------------------------------------- Payments

export function Payments({ site }: { site: Site | null }) {
  const s = useLoad<PaymentsPage>(api.payments);
  return (
    <>
      <PageHead title="Payments" sub="What you owe, what is coming, and what you have paid." />
      <State s={s} what="your payments">
        {p => {
          if (!p.customer) return <NoAccount />;
          const overdueSum = p.overdue.reduce((a, d) => a + d.totalOutstanding, 0);
          const dueSum = p.outstanding.reduce((a, d) => a + d.totalOutstanding, 0);
          const total = overdueSum + dueSum;
          const nextDate = p.upcoming[0]?.dueDate ?? p.outstanding[0]?.dueDate;
          return (
            <>
              <div className="pay-grid">
                <div className="total-card">
                  <span className="muted">Total to pay now</span>
                  <span className="big">{total > 0 ? rupeesShort(total) : 'Nothing due'}</span>
                  <span>{total === 0 ? (nextDate ? `You are all paid up. Next instalment ${dateOnly(nextDate)}.` : 'You are all paid up.')
                    : overdueSum > 0 ? `${rupeesShort(overdueSum)} is overdue.${dueSum > 0 ? ' The rest is due soon.' : ''}` : 'All of it is due soon.'}</span>
                </div>
                <div className="card" style={{ marginBottom: 0 }}>
                  <h2>How to pay</h2>
                  {p.howToPay ? <p className="howto">{p.howToPay}</p>
                    : <p className="muted">Ask your organiser how to pay{site?.helpPhone ? <>: <a href={`tel:${site.helpPhone.replace(/[^+\d]/g, '')}`}>{site.helpPhone}</a></> : ''}. Once your payment is recorded it moves to your history below.</p>}
                  <p className="small muted" style={{ marginBottom: 0 }}>Payments are recorded by our team. This page only shows what is due.</p>
                </div>
              </div>

              <nav className="jump" aria-label="Jump to a section">
                <a href="#pay-overdue">Overdue {p.overdue.length}</a><a href="#pay-due">Due now {p.outstanding.length}</a><a href="#pay-upcoming">Coming up {p.upcoming.length}</a><a href="#pay-history">History</a>
              </nav>

              <section className={p.overdue.length > 0 ? 'card card-bad' : 'card'} id="pay-overdue">
                <div className="card-head"><h2>Overdue</h2>{overdueSum > 0 && <strong>{rupeesShort(overdueSum)}</strong>}</div>
                {p.overdue.length === 0 ? <Empty>Nothing is overdue. Well done.</Empty> : <ul className="rows">{p.overdue.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
              </section>
              <section className="card" id="pay-due">
                <div className="card-head"><h2>Due now</h2>{dueSum > 0 && <span className="muted">Total {rupeesShort(dueSum)}</span>}</div>
                {p.outstanding.length === 0 ? <Empty>Nothing else is due right now.</Empty> : <ul className="rows">{p.outstanding.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
              </section>
              <section className="card" id="pay-upcoming">
                <h2>Coming up</h2>
                {p.upcoming.length === 0 ? <Empty>Nothing in the next two months.</Empty> : (
                  <>
                    <div className="timeline" aria-hidden="true"><i /><b /><i /><b /><i /></div>
                    <div className="timeline-cap"><span>Today</span><span>{dateOnly(p.upcoming[0].dueDate)}</span><span>{dateOnly(p.upcoming[p.upcoming.length - 1].dueDate)}</span></div>
                    <ul className="rows" style={{ marginTop: 10 }}>{p.upcoming.map((d, i) => <DueRow key={i} d={d} />)}</ul>
                  </>
                )}
              </section>
              <section className="card" id="pay-history">
                <h2 style={{ marginBottom: 6 }}>Payment history</h2>
                {p.history.length === 0 ? <Empty>No payments recorded yet.</Empty> : (
                  <ul className="rows">
                    {p.history.map(h => (
                      <li className="row" key={h.paymentRef}>
                        <div><strong>{h.groupName}</strong> <span className="muted">ticket {h.ticketNo}</span><div className="small muted">{dateOnly(h.businessDate)}. {titleCase(h.channel)}. Ref {h.paymentRef}</div></div>
                        <div className="amount">{rupees(h.amount)}{h.status !== 'POSTED' && <div><Badge tone="neutral">{titleCase(h.status)}</Badge></div>}</div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          );
        }}
      </State>
    </>
  );
}

// ---------------------------------------------------------------- Waiting on you

export function Approvals() {
  const s = useLoad<MemberRequest[]>(api.requests);
  return (
    <>
      <PageHead title="Waiting on you" sub="Requests about your tickets that our team is handling." />
      <State s={s} what="your requests">
        {rows => {
          const open = rows.filter(r => r.inProgress);
          const done = rows.filter(r => !r.inProgress);
          return (
            <>
              <p className="note">You do not need to do anything here: our team handles these. If something looks wrong, please call your organiser.</p>
              <div className="grid2">
                <section className="card">
                  <h2 style={{ marginBottom: 6 }}>In progress</h2>
                  {open.length === 0 ? <Empty art={<RelaxArt />}>Nothing is waiting on you.</Empty> : <ul className="rows">{open.map((r, i) => <RequestRow key={i} r={r} />)}</ul>}
                </section>
                <section className="card">
                  <h2 style={{ marginBottom: 6 }}>Decided</h2>
                  {done.length === 0 ? <Empty>Nothing yet.</Empty> : <ul className="rows">{done.map((r, i) => <RequestRow key={i} r={r} />)}</ul>}
                </section>
              </div>
            </>
          );
        }}
      </State>
    </>
  );
}

// ---------------------------------------------------------------- Offers

export function Offers({ ov }: { ov: Loaded<Overview> }) {
  return (
    <>
      <PageHead title="Offers for you" sub="News and special offers from Honest Savers Chit Fund." />
      <State s={ov} what="offers">
        {o => (o.offers.length === 0 ? <div className="card"><Empty art={<LampArt />}>No offers right now. Please check back soon.</Empty></div> : <OfferCards offers={o.offers} feature />)}
      </State>
    </>
  );
}

// ---------------------------------------------------------------- Auctions

const BID_ERRORS: Record<string, string> = {
  BID_NOT_ALLOWED: 'That bid is not allowed right now.',
  BID_OUT_OF_BOUNDS: 'The discount is outside the allowed range for this auction.',
  BID_NOT_HIGHER: 'Your bid must be higher than the current best bid.',
  TICKET_NOT_ELIGIBLE: 'That ticket cannot bid in this auction.',
  TOO_MANY_REQUESTS: 'Too many attempts. Please wait a moment.',
  NOT_SIGNED_IN: 'Your session has ended. Reload the page and sign in again.',
};

const QUICK = [500, 1000, 5000];

function AuctionCard({ a, onChange }: { a: Auction; onChange: (all: Auction[]) => void }) {
  const [ticket, setTicket] = useState<number>(a.eligibleTickets[0] ?? 0);
  const [amount, setAmount] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null);
  const keyRef = useRef<{ fp: string; key: string } | null>(null);

  function keyFor(fp: string) {
    if (!keyRef.current || keyRef.current.fp !== fp) keyRef.current = { fp, key: newKey() };
    return keyRef.current.key;
  }

  const live = a.state === 'LIVE';
  const value = Number(amount);
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0 && value >= a.minDiscount && value <= a.maxDiscount;
  const best = !a.bidsHidden && a.highestBid ? a.highestBid : null;

  async function place() {
    setBusy(true); setMsg(null);
    try {
      const body = { groupCode: a.groupCode, cycleNo: a.cycleNo, ticketNo: ticket, discountAmount: value };
      const r = await api.placeBid(body, keyFor(JSON.stringify(body)));
      keyRef.current = null;
      onChange(r.auctions);
      setMsg({ tone: 'good', text: `Bid of ${rupeesShort(value)} placed for ticket ${ticket}.` });
      setAmount(''); setConfirming(false);
    } catch (e) {
      setMsg({ tone: 'bad', text: e instanceof ApiError ? (BID_ERRORS[e.code] ?? e.message) : String(e) });
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(seq: number) {
    setBusy(true); setMsg(null);
    try {
      const body = { groupCode: a.groupCode, cycleNo: a.cycleNo, bidSeq: seq };
      const r = await api.withdrawBid(body, keyFor('w' + JSON.stringify(body)));
      keyRef.current = null;
      onChange(r.auctions);
      setMsg({ tone: 'good', text: 'Your bid was withdrawn.' });
    } catch (e) {
      setMsg({ tone: 'bad', text: e instanceof ApiError ? (BID_ERRORS[e.code] ?? e.message) : String(e) });
    } finally {
      setBusy(false);
    }
  }

  const info = (
    <div>
      <GavelArt />
      <h2 style={{ margin: '14px 0 8px' }}>{a.groupName}, instalment {a.cycleNo}</h2>
      <dl className="facts">
        <div><dt>{a.state === 'UPCOMING' ? 'Auction date' : 'Auction'}</dt><dd>{dateOnly(a.auctionDate)}</dd></div>
        {live && a.openedAt && <div><dt>Opened</dt><dd>{dateTime(a.openedAt)}</dd></div>}
        {!a.lastCycle && <div><dt>Discount you can bid</dt><dd>{rupeesShort(a.minDiscount)} to {rupeesShort(a.maxDiscount)}</dd></div>}
        {live && <div><dt>Best bid so far</dt><dd>{a.bidsHidden ? 'Hidden until the auction closes' : a.highestBid ? rupeesShort(a.highestBid) : 'No bids yet'}</dd></div>}
        {a.state === 'CLOSED' && a.winningDiscount !== undefined && a.winningDiscount !== null && <div><dt>Winning discount</dt><dd>{rupeesShort(a.winningDiscount)}</dd></div>}
      </dl>
      {live && <p className="explain">You bid the discount you are willing to give up. The organiser sets the rules and closes the auction.{a.bidsHidden ? ' Bids are sealed: you will not see other bids until it closes.' : ''}</p>}
    </div>
  );

  const bidding = (
    <div className="bid">
      {a.myBids.length > 0 && (
        <>
          <h3>Your bids</h3>
          <ul className="rows">
            {a.myBids.map(b => (
              <li className="row" key={b.bidSeq}>
                <div>Ticket {b.ticketNo}: <strong>{rupeesShort(b.discountAmount)}</strong>{' '}
                  {b.leading ? <Badge tone="good">Best bid</Badge> : b.status === 'VALID' && live && !a.bidsHidden ? <Badge tone="warn">Outbid</Badge> : null}
                  {b.status !== 'VALID' && <Badge tone="neutral">{titleCase(b.status)}</Badge>}
                </div>
                {live && b.status === 'VALID' && <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => withdraw(b.bidSeq)}>Withdraw</button>}
              </li>
            ))}
          </ul>
        </>
      )}
      {live && a.canBid && a.eligibleTickets.length > 0 && !a.lastCycle && (
        <form className="bid" onSubmit={e => { e.preventDefault(); if (valid) setConfirming(true); }}>
          <h3>Place a bid</h3>
          {a.eligibleTickets.length > 1 && (
            <label>Ticket
              <select value={ticket} onChange={e => { setTicket(Number(e.target.value)); setConfirming(false); }}>
                {a.eligibleTickets.map(t => <option key={t} value={t}>Ticket {t}</option>)}
              </select>
            </label>
          )}
          <label>Discount you will give up (Rs.)
            <input inputMode="decimal" value={amount} onChange={e => { setAmount(e.target.value); setConfirming(false); }} placeholder={`${a.minDiscount} to ${a.maxDiscount}`} />
          </label>
          {best !== null && (
            <div className="quick" role="group" aria-label="Quick amounts above the best bid">
              {QUICK.map(q => {
                const v = Math.min(a.maxDiscount, best + q);
                return <button type="button" key={q} className="chip" onClick={() => { setAmount(String(v)); setConfirming(false); }}>{rupeesShort(v)} (+{q.toLocaleString('en-IN')})</button>;
              })}
            </div>
          )}
          {amount.trim() !== '' && !valid && <p className="error small" style={{ margin: 0 }}>Enter an amount from {rupeesShort(a.minDiscount)} to {rupeesShort(a.maxDiscount)}.</p>}
          {!confirming && <button className="btn" type="submit" disabled={!valid || busy}>Review bid</button>}
          {confirming && (
            <div className="sheet">
              <strong>Bid {rupeesShort(value)} for ticket {ticket}?</strong>
              <div className="actions" style={{ marginTop: 10 }}>
                <button type="button" className="btn" disabled={busy} onClick={place}>{busy ? 'Placing...' : 'Confirm bid'}</button>
                <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setConfirming(false)}>Cancel</button>
              </div>
            </div>
          )}
        </form>
      )}
      {live && !a.canBid && a.cannotBidReason && <p className="sheet" style={{ margin: 0 }}>{a.cannotBidReason}</p>}
      {a.state === 'UPCOMING' && <p className="muted" style={{ margin: 0 }}>Bidding opens when the organiser starts the auction. There is no fixed closing time: the organiser closes it.</p>}
      {msg && <p className={msg.tone === 'good' ? 'ok' : 'error'} role="status" style={{ margin: 0 }}>{msg.text}</p>}
    </div>
  );

  if (a.state === 'CLOSED') {
    return (
      <section className="card">
        <div className="card-head"><h2>{a.groupName}, instalment {a.cycleNo}</h2><Badge tone="neutral">Closed</Badge></div>
        {a.iWon && (
          <div className="winner"><ConfettiArt /><div><strong style={{ fontSize: 18 }}>Your ticket {a.winningTicketNo} won this auction</strong><div className="small">{a.prize ? `Prize ${rupeesShort(a.prize)}. ` : ''}{a.winningDiscount != null ? `Winning discount ${rupeesShort(a.winningDiscount)}.` : ''}</div></div></div>
        )}
        <div className="small muted">Auction on {dateOnly(a.auctionDate)}{a.winningDiscount != null && !a.iWon ? `. Winning discount ${rupeesShort(a.winningDiscount)}.` : '.'}</div>
        {a.myBids.length > 0 && <div className="small muted">Your bids: {a.myBids.map(b => `ticket ${b.ticketNo} ${rupeesShort(b.discountAmount)}`).join(', ')}</div>}
      </section>
    );
  }
  return (
    <section className={live ? 'card card-live' : 'card'}>
      <div className="card-head" style={{ marginBottom: 14 }}>
        <span />
        <Badge tone={live ? 'good' : 'info'}>{live ? 'Live' : 'Upcoming'}</Badge>
      </div>
      <div className="auction">{info}{bidding}</div>
    </section>
  );
}

export function Auctions() {
  const s = useLoad<Auction[]>(api.auctions, d => (d && d.some(a => a.state === 'LIVE') ? 8000 : null));
  return (
    <>
      <PageHead title="Auctions" sub="Bid for the pot in your groups. The organiser opens and closes each auction." />
      <State s={s} what="auctions">
        {rows => {
          if (rows.length === 0) return <div className="card"><Empty art={<GavelArt />}>No auctions in your groups right now.</Empty></div>;
          const live = rows.filter(a => a.state === 'LIVE');
          const soon = rows.filter(a => a.state === 'UPCOMING');
          const done = rows.filter(a => a.state === 'CLOSED');
          const key = (a: Auction) => `${a.groupCode}-${a.cycleNo}`;
          return (
            <>
              <h2 style={{ margin: '0 0 12px' }}>Open now</h2>
              {live.length === 0 ? <div className="card"><Empty art={<GavelArt />}>No auction is open right now.</Empty></div> : live.map(a => <AuctionCard key={key(a)} a={a} onChange={s.set} />)}
              <h2 style={{ margin: '24px 0 12px' }}>Coming up</h2>
              {soon.length === 0 ? <div className="card"><Empty>No upcoming auctions.</Empty></div> : soon.map(a => <AuctionCard key={key(a)} a={a} onChange={s.set} />)}
              {done.length > 0 && <><h2 style={{ margin: '24px 0 12px' }}>Recently closed</h2>{done.map(a => <AuctionCard key={key(a)} a={a} onChange={s.set} />)}</>}
            </>
          );
        }}
      </State>
    </>
  );
}

// ---------------------------------------------------------------- More (phone)

export function More({ name, email, site, count, go, onSignOut }: { name: string; email: string; site: Site | null; count: { requests: number; offers: number }; go: (v: string) => void; onSignOut: () => void }) {
  const tel = site?.helpPhone ? site.helpPhone.replace(/[^+\d]/g, '') : '';
  return (
    <>
      <PageHead title="More" />
      <div className="card" style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
        <span className="avatar" style={{ width: 52, height: 52 }}>{name.charAt(0).toUpperCase()}</span>
        <div><strong>{name}</strong><div className="small muted">{email}</div></div>
      </div>
      <div className="card" style={{ padding: '0 16px' }}>
        <ul className="rows">
          <li className="row"><button className="link" style={{ textDecoration: 'none', color: 'var(--ink)' }} onClick={() => go('approvals')}>Waiting on you</button>{count.requests > 0 && <span className="count">{count.requests}</span>}</li>
          <li className="row"><button className="link" style={{ textDecoration: 'none', color: 'var(--ink)' }} onClick={() => go('auctions')}>Auctions</button><Icon name="arrow" size={18} /></li>
          <li className="row"><button className="link" style={{ textDecoration: 'none', color: 'var(--ink)' }} onClick={() => go('offers')}>Offers</button>{count.offers > 0 && <span className="count">{count.offers}</span>}</li>
          <li className="row"><button className="link" style={{ textDecoration: 'none', color: 'var(--ink)' }} onClick={() => go('payments')}>How to pay</button><Icon name="arrow" size={18} /></li>
          {tel && <li className="row"><a href={`tel:${tel}`} style={{ color: 'var(--ink)', fontWeight: 700, textDecoration: 'none' }}>Call my organiser</a><span className="muted">{site?.helpPhone}</span></li>}
        </ul>
      </div>
      <button className="btn btn-ghost" style={{ width: '100%' }} onClick={onSignOut}><Icon name="out" size={18} /> Sign out</button>
    </>
  );
}

// kept for the sign-in page
export { CoinsArt, LampArt };
