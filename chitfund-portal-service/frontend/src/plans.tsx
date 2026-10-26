import { ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, JoinRequest, Plan, api } from './api';
import { dateOnly, rupeesShort as rupees } from './format';
import { useLoad } from './member';

// Plan browser laid out like the supplied design: category bar, then a grid of photo cards, each with "Request Seat".
// A request does not take a seat; the team reviews it first.

interface Cat { key: string; label: string; sub: string; blurb: string }
export const CATEGORIES: Cat[] = [
  { key: 'EDUCATION', label: 'Education Plan', sub: 'Secure tuition', blurb: 'Set money aside for fees and higher education.' },
  { key: 'HOME', label: 'Home Plan', sub: 'Dream property', blurb: 'Build up for a down payment or a home of your own.' },
  { key: 'WEDDING', label: 'Wedding Plan', sub: 'Grand celebrations', blurb: 'Plan the celebration without borrowing at high interest.' },
  { key: 'SHORT_TERM', label: 'Short term Plan', sub: 'High liquidity', blurb: 'A shorter chit for money you need sooner.' },
  { key: 'RETIREMENT', label: 'Retirement Plan', sub: 'Peaceful future', blurb: 'Save steadily over a long period for later life.' },
  { key: 'BUSINESS', label: 'Business Plan', sub: 'Expand enterprise', blurb: 'Working capital for stock, equipment or growth.' },
];
const CAT = (k: string) => CATEGORIES.find(c => c.key === k);

function CatIcon({ k, size = 22 }: { k: string; size?: number }) {
  const p: Record<string, ReactNode> = {
    EDUCATION: <path d="M2 9l10-5 10 5-10 5zM6 11.5V16c0 1.5 3 3 6 3s6-1.5 6-3v-4.5" />,
    HOME: <path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10" />,
    WEDDING: <><circle cx="9" cy="14" r="5" /><circle cx="16" cy="14" r="5" /><path d="M9 4l1.5 2.5L9 9 7.5 6.5z" /></>,
    SHORT_TERM: <><circle cx="12" cy="13" r="8" /><path d="M12 8v5l3 2M9 2h6" /></>,
    RETIREMENT: <path d="M12 3v18M5 9c0-3 3-6 7-6s7 3 7 6M5 9h14M7 21h10" />,
    BUSINESS: <path d="M3 8h18v12H3zM9 8V5h6v3M3 13h18" />,
    ALL: <path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{p[k] ?? p.ALL}</svg>;
}

// Photos for each plan type (Unsplash). If one cannot load, the card shows a dark backdrop with the category icon instead.
const unsplash = (id: string) => `https://images.unsplash.com/photo-${id}?q=80&w=800&auto=format&fit=crop`;
export const PLAN_PHOTO: Record<string, string> = {
  EDUCATION: unsplash('1523050854058-8df90110c9f1'),
  HOME: unsplash('1600585154340-be6161a56a0c'),
  WEDDING: unsplash('1519741497674-611481863552'),
  SHORT_TERM: unsplash('1559526324-4b87b5e36e44'),
  RETIREMENT: unsplash('1507525428034-b723cf961d3e'),
  BUSINESS: unsplash('1486406146926-c627a92ad1ab'),
};

const freq = (f: string) => (f === 'WEEKLY' ? 'week' : f === 'FORTNIGHTLY' ? 'fortnight' : 'month');
const freqPlural = (f: string) => (f === 'WEEKLY' ? 'Weeks' : f === 'FORTNIGHTLY' ? 'Fortnights' : 'Months');
const instalmentWord = (f: string) => (f === 'WEEKLY' ? 'Weekly' : f === 'FORTNIGHTLY' ? 'Fortnightly' : 'Monthly');

export function PlanCard({ plan, onInvest, label, disabled }: { plan: Plan; onInvest: (p: Plan) => void; label?: string; disabled?: boolean }) {
  const [bad, setBad] = useState(false);
  const src = PLAN_PHOTO[plan.category];
  const cat = CAT(plan.category);
  const full = plan.seatsLeft <= 0;
  const low = !full && plan.seatsLeft <= Math.max(2, Math.floor(plan.tickets * 0.2));
  return (
    <article className="plan-card" data-category={plan.category}>
      <div className="plan-photo">
        {src && !bad && <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBad(true)} />}
        {(!src || bad) && <span className="plan-photo-icon"><CatIcon k={plan.category} size={56} /></span>}
        <div className="plan-photo-fade" />
        <span className={`plan-tag tag-${plan.category}`}>{cat?.label ?? 'Plan'}</span>
        {(plan.branch || plan.state) && <span className="plan-where">{[plan.branch, plan.state].filter(Boolean).join(', ')}</span>}
      </div>
      <div className="plan-body">
        <div>
          <h3 className="plan-title">{plan.name}</h3>
          <p className="plan-blurb">{cat?.blurb ?? 'A chit group open for new members.'}</p>
        </div>
        <dl className="plan-facts">
          <div><dt>Total Value</dt><dd className="v-white">{rupees(plan.chitValue)}</dd></div>
          <div><dt>{instalmentWord(plan.frequency)} Instalment</dt><dd className="v-gold">{rupees(plan.subscription)}</dd></div>
          <div><dt>Duration</dt><dd className="v-grey">{plan.cycles} {freqPlural(plan.frequency)}</dd></div>
          <div><dt>Seats Available</dt><dd className={full ? 'v-red' : low ? 'v-amber' : 'v-green'}>{full ? 'Full' : `${plan.seatsLeft} / ${plan.tickets} Seats`}</dd></div>
        </dl>
        <div className="plan-start">Starts {dateOnly(plan.startDate)}</div>
        <button className="plan-btn" onClick={() => onInvest(plan)} disabled={full || disabled}>
          <span>{label ?? 'Request Seat'}</span>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
        </button>
      </div>
    </article>
  );
}

export function PlanBrowser({ onInvest, joined, heading }: { onInvest: (p: Plan) => void; joined?: Set<string>; heading?: string }) {
  const s = useLoad<Plan[]>(api.plans);
  const [state, setState] = useState('');
  const [cat, setCat] = useState('ALL');
  const plans = s.data ?? [];
  const states = useMemo(() => Array.from(new Set(plans.map(p => p.state).filter(Boolean))).sort(), [plans]);
  useEffect(() => {
    if (!state && states.length > 0) {
      const n = (x: string) => plans.filter(p => p.state === x).length;
      setState([...states].sort((a, b) => n(b) - n(a))[0]);
    }
  }, [states, state, plans]);
  const inState = plans.filter(p => !state || !p.state || p.state === state);
  const count = (k: string) => (k === 'ALL' ? inState.length : inState.filter(p => p.category === k).length);
  const shown = cat === 'ALL' ? inState : inState.filter(p => p.category === cat);

  return (
    <section className="deals">
      <div className="deals-head">
        <h2>{heading ?? 'Open plans category'}</h2>
        <div className="deals-right">
          <span className="deals-hint">Showing all available groups</span>
          {states.length > 1 && (
            <label className="state-pick">
              <span className="sr">State</span>
              <select value={state} onChange={e => setState(e.target.value)} aria-label="State">
                {states.map(x => <option key={x} value={x}>{x}</option>)}
              </select>
            </label>
          )}
        </div>
      </div>
      <div className="catbar" role="tablist" aria-label="Plan type">
        <div className="catbar-in">
          <button role="tab" aria-selected={cat === 'ALL'} className="cat cat-ALL" onClick={() => setCat('ALL')}>
            <span className="cat-ic"><CatIcon k="ALL" size={22} /></span>
            <span className="cat-name">All Plans</span>
            <span className="cat-sub">{count('ALL')} active group{count('ALL') === 1 ? '' : 's'}</span>
          </button>
          {CATEGORIES.map(c => (
            <button key={c.key} role="tab" aria-selected={cat === c.key} className={`cat cat-${c.key}`} onClick={() => setCat(c.key)}>
              <span className="cat-ic"><CatIcon k={c.key} size={22} /></span>
              <span className="cat-name">{c.label}</span>
              <span className="cat-sub">{count(c.key) > 0 ? `${count(c.key)} open` : c.sub}</span>
            </button>
          ))}
        </div>
      </div>
      {s.loading && <p className="muted" role="status">Loading plans...</p>}
      {s.error && <p className="error">{s.error}</p>}
      {!s.loading && !s.error && shown.length === 0 && (
        <div className="empty-plans">
          <h3>No plans open here right now</h3>
          <p>Try another plan type, or check back soon. New groups open every few weeks.</p>
        </div>
      )}
      {shown.length > 0 && (
        <div className="plan-grid">
          {shown.map(p => <PlanCard key={p.groupCode} plan={p} onInvest={onInvest} disabled={joined?.has(p.groupCode)} label={joined?.has(p.groupCode) ? 'Request sent' : undefined} />)}
        </div>
      )}
      <p className="deals-note">Sending a request does not take a seat. Our team reviews it and contacts you before anything is charged.</p>
    </section>
  );
}

// ---------------------------------------------------------------- signed in: browse + request

const JR: Record<string, [string, string]> = {
  PENDING: ['Waiting for approval', 'badge-warn'], APPROVED: ['Approved', 'badge-good'], DECLINED: ['Not approved', 'badge-neutral'], WITHDRAWN: ['Withdrawn', 'badge-neutral'],
};

export function PlansPage({ initialJoin }: { initialJoin?: string }) {
  const reqs = useLoad<JoinRequest[]>(api.joinRequests);
  const plans = useLoad<Plan[]>(api.plans);
  const [pick, setPick] = useState<Plan | null>(null);
  const [tickets, setTickets] = useState(1);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const key = useRef(`JOIN-${Math.random().toString(36).slice(2)}-${Date.now()}`);
  const live = new Set((reqs.data ?? []).filter(r => r.status === 'PENDING' || r.status === 'APPROVED').map(r => r.groupCode));

  useEffect(() => {
    if (initialJoin && plans.data && !pick) {
      const p = plans.data.find(x => x.groupCode === initialJoin);
      if (p && !live.has(p.groupCode)) setPick(p);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialJoin, plans.data]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(t);
  }, [toast]);

  async function send() {
    if (!pick) return;
    setBusy(true); setErr(null);
    try {
      await api.requestJoin({ groupCode: pick.groupCode, tickets }, key.current + pick.groupCode);
      setToast(pick.name); setPick(null); setTickets(1); reqs.reload();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <>
      <div className="page-head"><h1>Choose a plan</h1><p>Pick a chit that fits your goal. Send a request and our team will confirm your seat.</p></div>
      <PlanBrowser onInvest={p => { setErr(null); setPick(p); }} joined={live} />
      {pick && (
        <div className="modal-back" role="dialog" aria-modal="true" aria-label="Seat reservation request" onClick={e => { if (e.target === e.currentTarget) setPick(null); }}>
          <div className="modal">
            <button className="modal-x" onClick={() => setPick(null)} aria-label="Close">&times;</button>
            <span className="modal-chip">Seat Reservation Request</span>
            <h3 className="modal-title">{pick.name}</h3>
            <p className="modal-text">Our team will check availability and contact you to confirm your seat. Nothing is charged by sending this request.</p>
            <dl className="modal-box">
              <div><dt>Total Chit Value:</dt><dd className="v-white">{rupees(pick.chitValue)}</dd></div>
              <div><dt>{instalmentWord(pick.frequency)} Instalment:</dt><dd className="v-gold">{rupees(pick.subscription)}</dd></div>
              <div><dt>Duration:</dt><dd className="v-grey">{pick.cycles} {freqPlural(pick.frequency)}</dd></div>
            </dl>
            <label className="modal-field"><span>How many tickets?</span>
              <select value={tickets} onChange={e => setTickets(Number(e.target.value))}>
                {Array.from({ length: Math.max(1, Math.min(3, pick.seatsLeft)) }, (_, i) => i + 1).map(n => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
            {err && <p className="error" role="alert">{err}</p>}
            <button className="modal-go" onClick={send} disabled={busy}><span>{busy ? 'Sending...' : 'Confirm & Send Request'}</span></button>
          </div>
        </div>
      )}
      <section style={{ marginTop: 34 }}>
        <h2 style={{ marginBottom: 14 }}>My join requests</h2>
        {reqs.data && reqs.data.length === 0 && <p className="muted">You have not sent any requests yet.</p>}
        {reqs.data && reqs.data.length > 0 && (
          <div className="card" style={{ padding: '0 18px' }}>
            <ul className="rows">
              {reqs.data.map(r => {
                const [t, c] = JR[r.status] ?? [r.status, 'badge-neutral'];
                return (
                  <li className="row" key={r.id}>
                    <div><strong>{r.groupName}</strong><div className="small muted">{r.tickets} ticket{r.tickets === 1 ? '' : 's'}, sent {dateOnly(r.requestedAt)}{r.note ? `. ${r.note}` : ''}</div></div>
                    <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                      <span className={`badge ${c}`}>{t}</span>
                      {r.status === 'PENDING' && <button className="link small" onClick={async () => { await api.withdrawJoin(r.id); reqs.reload(); }}>Withdraw</button>}
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </section>
      <div className={toast ? 'toast toast-on' : 'toast'} role="status" aria-live="polite">
        <span className="toast-ic">&#10003;</span>
        <div><strong>Seat Request Sent!</strong><span>{toast ? `Your request for ${toast} has reached our team. We will be in touch.` : ''}</span></div>
      </div>
    </>
  );
}
