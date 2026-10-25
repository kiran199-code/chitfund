import { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, Auction, Due, MemberRequest, Offer, Overview, PaymentsPage, Ticket, api } from './api';
import { dateOnly, dateTime, newKey, rupees, rupeesShort, safeUrl, titleCase } from './format';

// ---------------------------------------------------------------- loading helpers

export interface Loaded<T> { data: T | null; error: string | null; loading: boolean; reload: () => void; set: (v: T) => void }

/** Loads once, on demand again with reload(); optional polling while the tab is visible. */
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
  if (s.loading) return <p className="muted">Loading {what}...</p>;
  return (
    <div className="card">
      <p className="error" role="alert">{s.error ?? `Could not load ${what}.`}</p>
      <button className="btn btn-quiet" onClick={s.reload}>Try again</button>
    </div>
  );
}

const Empty = ({ children }: { children: ReactNode }) => <p className="muted empty">{children}</p>;

function Badge({ tone, children }: { tone: 'good' | 'warn' | 'bad' | 'info' | 'neutral'; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

// ---------------------------------------------------------------- shared pieces

function NoAccount() {
  return (
    <section className="card narrow">
      <h1>No chit account yet</h1>
      <p>This Google account does not have any chit tickets yet.</p>
      <p className="muted">
        If the organiser invited you, open the link in the invitation e-mail and accept it. Make sure the invitation was sent to the same e-mail
        address you signed in with.
      </p>
    </section>
  );
}

function DueRow({ d }: { d: Due }) {
  const overdue = d.view === 'OVERDUE' || d.daysOverdue > 0;
  return (
    <li className="row">
      <div>
        <strong>{d.groupName}</strong> <span className="muted">ticket {d.ticketNo}, instalment {d.cycleNo}</span>
        <div className="small muted">
          {overdue ? `${d.daysOverdue} day${d.daysOverdue === 1 ? '' : 's'} late, was due ${dateOnly(d.dueDate)}` : `Due ${dateOnly(d.dueDate)}`}
          {d.lateFeeOutstanding > 0 ? ` (includes late fee ${rupeesShort(d.lateFeeOutstanding)})` : ''}
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
      <div>
        <strong>{text}</strong>
        <div className="small muted">{r.groupName}{r.requestedAt ? `, requested ${dateOnly(r.requestedAt)}` : ''}{r.amount ? `, ${rupeesShort(r.amount)}` : ''}</div>
      </div>
      <div className="amount"><Badge tone={r.inProgress ? 'warn' : r.status === 'REJECTED' || r.status === 'CANCELLED' ? 'neutral' : 'good'}>{titleCase(r.status)}</Badge></div>
    </li>
  );
}

export function OfferCards({ offers }: { offers: Offer[] }) {
  if (offers.length === 0) return null;
  return (
    <div className="offers">
      {offers.map(o => {
        const link = safeUrl(o.linkUrl);
        const img = safeUrl(o.imageUrl);
        return (
          <article className="offer" key={o.id}>
            {img && <img src={img} alt="" loading="lazy" referrerPolicy="no-referrer" />}
            <h3>{o.title}</h3>
            {o.body && <p>{o.body}</p>}
            <p className="small muted">{o.endsOn ? `Until ${dateOnly(o.endsOn)}` : `From ${dateOnly(o.startsOn)}`}</p>
            {link && <a className="btn btn-quiet" href={link} target="_blank" rel="noopener noreferrer">Learn more</a>}
          </article>
        );
      })}
    </div>
  );
}

function AuctionLine({ a }: { a: Auction }) {
  return (
    <li className="row">
      <div>
        <strong>{a.groupName}</strong> <span className="muted">instalment {a.cycleNo}</span>
        <div className="small muted">{a.state === 'LIVE' ? 'Bidding is open now' : `Auction on ${dateOnly(a.auctionDate)}`}</div>
      </div>
      <div className="amount">{a.state === 'LIVE' ? <Badge tone="good">Live</Badge> : <Badge tone="info">Upcoming</Badge>}</div>
    </li>
  );
}

// ---------------------------------------------------------------- Dashboard

export function Dashboard({ go }: { go: (view: string) => void }) {
  const s = useLoad<Overview>(api.dashboard);
  return (
    <State s={s} what="your dashboard">
      {o => {
        if (!o.customer && o.offers.length === 0) return <NoAccount />;
        if (!o.customer) return (<><NoAccount /><h2>Offers</h2><OfferCards offers={o.offers} /></>);
        const sum = o.summary;
        return (
          <>
            {o.unavailable.length > 0 && (
              <p className="banner" role="status">Some information could not be loaded right now ({o.unavailable.join(', ')}). Reload in a moment.</p>
            )}
            <div className="tiles">
              <button className="tile" onClick={() => go('tickets')}><span>My tickets</span><strong>{sum.ticketsHeld}</strong></button>
              <button className="tile" onClick={() => go('payments')}>
                <span>Next payment</span>
                <strong>{sum.nextPayment ? rupeesShort(sum.nextPayment.totalOutstanding) : 'None'}</strong>
                <em>{sum.nextPayment ? `due ${dateOnly(sum.nextPayment.dueDate)}` : 'nothing due'}</em>
              </button>
              <button className={sum.overdueCount > 0 ? 'tile tile-bad' : 'tile'} onClick={() => go('payments')}>
                <span>Overdue</span><strong>{sum.overdueCount > 0 ? rupeesShort(sum.overdueTotal) : 'None'}</strong>
                <em>{sum.overdueCount > 0 ? `${sum.overdueCount} instalment${sum.overdueCount === 1 ? '' : 's'}` : 'all paid up'}</em>
              </button>
              <button className={sum.liveAuctions > 0 ? 'tile tile-good' : 'tile'} onClick={() => go('auctions')}>
                <span>Auctions</span><strong>{sum.liveAuctions} live</strong><em>{sum.upcomingAuctions} upcoming</em>
              </button>
              <button className="tile" onClick={() => go('approvals')}><span>Approvals</span><strong>{sum.openRequests}</strong><em>in progress</em></button>
            </div>

            <section className="card">
              <div className="card-head"><h2>Payments to make</h2><button className="link" onClick={() => go('payments')}>See all</button></div>
              {o.nextPayments.length === 0 ? <Empty>Nothing due right now.</Empty> : <ul className="rows">{o.nextPayments.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
            </section>

            <section className="card">
              <div className="card-head"><h2>Auctions</h2><button className="link" onClick={() => go('auctions')}>See all</button></div>
              {o.auctions.length === 0 ? <Empty>No live or upcoming auctions.</Empty> : <ul className="rows">{o.auctions.map((a, i) => <AuctionLine key={i} a={a} />)}</ul>}
            </section>

            <section className="card">
              <div className="card-head"><h2>Waiting for approval</h2><button className="link" onClick={() => go('approvals')}>See all</button></div>
              {o.requests.length === 0 ? <Empty>Nothing is waiting.</Empty> : <ul className="rows">{o.requests.map((r, i) => <RequestRow key={i} r={r} />)}</ul>}
            </section>

            {o.offers.length > 0 && (
              <section>
                <div className="card-head"><h2>Offers for you</h2><button className="link" onClick={() => go('offers')}>See all</button></div>
                <OfferCards offers={o.offers.slice(0, 2)} />
              </section>
            )}
          </>
        );
      }}
    </State>
  );
}

// ---------------------------------------------------------------- My tickets

function TicketCard({ t }: { t: Ticket }) {
  const pct = t.cycleCount > 0 ? Math.min(100, Math.round((t.cyclesPaid / t.cycleCount) * 100)) : 0;
  return (
    <section className="card">
      <div className="card-head">
        <h2>{t.groupName} <span className="muted">ticket {t.ticketNo}</span></h2>
        <Badge tone={t.ticketStatus === 'ACTIVE' ? 'good' : 'neutral'}>{titleCase(t.ticketStatus)}</Badge>
      </div>
      <dl className="facts">
        <div><dt>Chit value</dt><dd>{rupeesShort(t.chitValue)}</dd></div>
        <div><dt>{titleCase(t.frequency)} subscription</dt><dd>{rupeesShort(t.subscription)}</dd></div>
        <div><dt>Started</dt><dd>{dateOnly(t.startDate)}</dd></div>
        <div><dt>Instalments paid</dt><dd>{t.cyclesPaid} of {t.cycleCount}</dd></div>
        <div><dt>Prize</dt><dd>{t.prizeWon ? `Won${t.prizedCycleNo ? ` in instalment ${t.prizedCycleNo}` : ''}` : 'Not won yet'}</dd></div>
        <div><dt>Dividends received</dt><dd>{rupeesShort(t.dividendsReceived)}</dd></div>
      </dl>
      <div className="bar" role="img" aria-label={`${pct} percent of instalments paid`}><span style={{ width: `${pct}%` }} /></div>
    </section>
  );
}

export function Tickets() {
  const s = useLoad<Ticket[]>(api.tickets);
  return (
    <State s={s} what="your tickets">
      {rows => (rows.length === 0
        ? <NoAccount />
        : <>{rows.map(t => <TicketCard key={`${t.groupCode}-${t.ticketNo}`} t={t} />)}</>)}
    </State>
  );
}

// ---------------------------------------------------------------- Payments

export function Payments() {
  const s = useLoad<PaymentsPage>(api.payments);
  return (
    <State s={s} what="your payments">
      {p => {
        if (!p.customer) return <NoAccount />;
        const total = [...p.overdue, ...p.outstanding].reduce((a, d) => a + d.totalOutstanding, 0);
        return (
          <>
            <p className="note">
              Payments are made to the organiser; this page only shows what is due. Ask the organiser how to pay. Once they record your payment it
              moves to your history.
            </p>
            <section className="card">
              <div className="card-head"><h2>Overdue</h2>{p.overdue.length > 0 && <Badge tone="bad">{rupeesShort(p.overdue.reduce((a, d) => a + d.totalOutstanding, 0))}</Badge>}</div>
              {p.overdue.length === 0 ? <Empty>Nothing overdue.</Empty> : <ul className="rows">{p.overdue.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
            </section>
            <section className="card">
              <div className="card-head"><h2>Due now</h2>{total > 0 && <span className="muted small">Total outstanding {rupeesShort(total)}</span>}</div>
              {p.outstanding.length === 0 ? <Empty>Nothing else is due right now.</Empty> : <ul className="rows">{p.outstanding.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
            </section>
            <section className="card">
              <h2>Coming up</h2>
              {p.upcoming.length === 0 ? <Empty>Nothing in the next two months.</Empty> : <ul className="rows">{p.upcoming.map((d, i) => <DueRow key={i} d={d} />)}</ul>}
            </section>
            <section className="card">
              <h2>Payment history</h2>
              {p.history.length === 0 ? <Empty>No payments recorded yet.</Empty> : (
                <ul className="rows">
                  {p.history.map(h => (
                    <li className="row" key={h.paymentRef}>
                      <div>
                        <strong>{h.groupName}</strong> <span className="muted">ticket {h.ticketNo}</span>
                        <div className="small muted">{dateOnly(h.businessDate)}, {titleCase(h.channel)}, ref {h.paymentRef}</div>
                      </div>
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
  );
}

// ---------------------------------------------------------------- Approvals

export function Approvals() {
  const s = useLoad<MemberRequest[]>(api.requests);
  return (
    <State s={s} what="your approvals">
      {rows => {
        const open = rows.filter(r => r.inProgress);
        const done = rows.filter(r => !r.inProgress);
        return (
          <>
            <p className="note">
              These are requests about your tickets that the organiser's team is handling. You do not need to act on them here; if something looks
              wrong, contact the organiser.
            </p>
            <section className="card">
              <h2>In progress</h2>
              {open.length === 0 ? <Empty>Nothing is waiting for approval.</Empty> : <ul className="rows">{open.map((r, i) => <RequestRow key={i} r={r} />)}</ul>}
            </section>
            {done.length > 0 && (
              <section className="card">
                <h2>Decided</h2>
                <ul className="rows">{done.map((r, i) => <RequestRow key={i} r={r} />)}</ul>
              </section>
            )}
          </>
        );
      }}
    </State>
  );
}

// ---------------------------------------------------------------- Offers

export function Offers() {
  const s = useLoad<Overview>(api.dashboard);
  return (
    <State s={s} what="offers">
      {o => (o.offers.length === 0 ? <Empty>There are no offers right now. Check back soon.</Empty> : <OfferCards offers={o.offers} />)}
    </State>
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

  return (
    <section className={live ? 'card card-live' : 'card'}>
      <div className="card-head">
        <h2>{a.groupName} <span className="muted">instalment {a.cycleNo}</span></h2>
        {a.state === 'LIVE' ? <Badge tone="good">Live</Badge> : a.state === 'UPCOMING' ? <Badge tone="info">Upcoming</Badge> : <Badge tone="neutral">Closed</Badge>}
      </div>
      <dl className="facts">
        <div><dt>{a.state === 'UPCOMING' ? 'Auction date' : 'Auction'}</dt><dd>{dateOnly(a.auctionDate)}</dd></div>
        {live && a.openedAt && <div><dt>Opened</dt><dd>{dateTime(a.openedAt)}</dd></div>}
        {!a.lastCycle && <div><dt>Discount you can bid</dt><dd>{rupeesShort(a.minDiscount)} to {rupeesShort(a.maxDiscount)}</dd></div>}
        {live && <div><dt>Best bid so far</dt><dd>{a.bidsHidden ? 'Sealed until the auction closes' : a.highestBid ? rupeesShort(a.highestBid) : 'No bids yet'}</dd></div>}
        {a.state === 'CLOSED' && a.winningDiscount !== undefined && a.winningDiscount !== null && <div><dt>Winning discount</dt><dd>{rupeesShort(a.winningDiscount)}</dd></div>}
      </dl>

      {a.state === 'CLOSED' && a.iWon && (
        <p className="banner banner-good">Your ticket {a.winningTicketNo} won this auction{a.prize ? `: prize ${rupeesShort(a.prize)}` : ''}.</p>
      )}
      {a.state === 'UPCOMING' && <p className="muted small">Bidding opens when the organiser starts the auction. There is no fixed closing time: the organiser closes it.</p>}

      {a.myBids.length > 0 && (
        <>
          <h3>Your bids</h3>
          <ul className="rows">
            {a.myBids.map(b => (
              <li className="row" key={b.bidSeq}>
                <div>Ticket {b.ticketNo}: <strong>{rupeesShort(b.discountAmount)}</strong>
                  {b.leading ? <> <Badge tone="good">Best bid</Badge></> : b.status === 'VALID' && live && !a.bidsHidden ? <> <Badge tone="warn">Outbid</Badge></> : null}
                  {b.status !== 'VALID' && <> <Badge tone="neutral">{titleCase(b.status)}</Badge></>}
                </div>
                {live && b.status === 'VALID' && <button className="btn btn-quiet" disabled={busy} onClick={() => withdraw(b.bidSeq)}>Withdraw</button>}
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
          {amount.trim() !== '' && !valid && <p className="error small">Enter an amount from {rupeesShort(a.minDiscount)} to {rupeesShort(a.maxDiscount)}.</p>}
          {!confirming && <button className="btn" type="submit" disabled={!valid || busy}>Review bid</button>}
          {confirming && (
            <div className="actions">
              <span>Bid {rupeesShort(value)} for ticket {ticket}?</span>
              <button type="button" className="btn" disabled={busy} onClick={place}>{busy ? 'Placing...' : 'Confirm bid'}</button>
              <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => setConfirming(false)}>Cancel</button>
            </div>
          )}
        </form>
      )}
      {live && !a.canBid && a.cannotBidReason && <p className="muted">{a.cannotBidReason}</p>}
      {msg && <p className={msg.tone === 'good' ? 'ok' : 'error'} role="status">{msg.text}</p>}
    </section>
  );
}

export function Auctions() {
  const s = useLoad<Auction[]>(api.auctions, d => (d && d.some(a => a.state === 'LIVE') ? 8000 : null));
  return (
    <State s={s} what="auctions">
      {rows => {
        if (rows.length === 0) return <Empty>No auctions in your groups right now.</Empty>;
        const live = rows.filter(a => a.state === 'LIVE');
        const soon = rows.filter(a => a.state === 'UPCOMING');
        const done = rows.filter(a => a.state === 'CLOSED');
        return (
          <>
            <h2>Live now</h2>
            {live.length === 0 ? <Empty>No auction is open right now.</Empty> : live.map(a => <AuctionCard key={`${a.groupCode}-${a.cycleNo}`} a={a} onChange={s.set} />)}
            <h2>Upcoming</h2>
            {soon.length === 0 ? <Empty>No upcoming auctions.</Empty> : soon.map(a => <AuctionCard key={`${a.groupCode}-${a.cycleNo}`} a={a} onChange={s.set} />)}
            {done.length > 0 && <><h2>Recently closed</h2>{done.map(a => <AuctionCard key={`${a.groupCode}-${a.cycleNo}`} a={a} onChange={s.set} />)}</>}
          </>
        );
      }}
    </State>
  );
}
