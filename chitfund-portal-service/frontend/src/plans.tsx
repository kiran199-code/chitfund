import { ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, JoinRequest, Plan, api } from './api';
import { Icon } from './brand';
import { dateOnly, rupeesShort as rupees } from './format';
import { useLoad } from './member';

// Plan browser in the style of a "best deals" shelf: state, category tabs, scrolling plan cards, "Invest now" (which sends a join request).

export const CATEGORIES: [string, string][] = [
  ['EDUCATION', 'Education Plan'], ['HOME', 'Home Plan'], ['WEDDING', 'Wedding Plan'], ['SHORT_TERM', 'Short term Plan'],
  ['RETIREMENT', 'Retirement Plan'], ['BUSINESS', 'Business Plan'], ['ALL', 'All Plans'],
];

function CatIcon({ k }: { k: string }) {
  const p: Record<string, ReactNode> = {
    EDUCATION: <path d="M2 9l10-5 10 5-10 5zM6 11.5V16c0 1.5 3 3 6 3s6-1.500 6-3v-4.500" />,
    HOME: <path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10" />,
    WEDDING: <><circle cx="9" cy="14" r="5" /><circle cx="16" cy="14" r="5" /><path d="M9 4l1.500 2.500L9 9 7.500 6.500z" /></>,
    SHORT_TERM: <><circle cx="12" cy="13" r="8" /><path d="M12 8v5l3 2M9 2h6" /></>,
    RETIREMENT: <path d="M12 3v18M5 9c0-3 3-6 7-6s7 3 7 6M5 9h14M7 21h10" />,
    BUSINESS: <path d="M3 8h18v12H3zM9 8V5h6v3M3 13h18" />,
    ALL: <path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />,
  };
  return <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{p[k]}</svg>;
}

// Photos for each plan type (Unsplash). If one cannot load, the card shows a plain coloured backdrop instead.
const unsplash = (id: string) => `https://images.unsplash.com/photo-${id}?q=80&w=800&auto=format&fit=crop`;
export const PLAN_PHOTO: Record<string, string> = {
  EDUCATION: unsplash('1523050854058-8df90110c9f1'),
  HOME: unsplash('1600585154340-be6161a56a0c'),
  WEDDING: unsplash('1519741497674-611481863552'),
  SHORT_TERM: unsplash('1559526324-4b87b5e36e44'),
  RETIREMENT: unsplash('1507525428034-b723cf961d3e'),
  BUSINESS: unsplash('1486406146926-c627a92ad1ab'),
};
const catLabel = (k: string) => CATEGORIES.find(c => c[0] === k)?.[1] ?? 'Plan';

function PlanPic({ category }: { category: string }) {
  const [bad, setBad] = useState(false);
  const src = PLAN_PHOTO[category];
  return (
    <div className="plan-pic">
      {src && !bad && <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBad(true)} />}
      {(!src || bad) && <span className="plan-pic-icon"><CatIcon k={category in PLAN_PHOTO ? category : 'ALL'} /></span>}
      <span className="plan-pic-tag">{catLabel(category)}</span>
    </div>
  );
}

const freq = (f: string) => (f === 'WEEKLY' ? 'week' : f === 'FORTNIGHTLY' ? 'fortnight' : 'month');
const freqPlural = (f: string) => (f === 'WEEKLY' ? 'weeks' : f === 'FORTNIGHTLY' ? 'fortnights' : 'months');

export function PlanCard({ plan, onInvest, label, disabled }: { plan: Plan; onInvest: (p: Plan) => void; label?: string; disabled?: boolean }) {
  const full = plan.seatsLeft <= 0;
  return (
    <article className="plan">
      <PlanPic category={plan.category} />
      <div className="plan-body">
      <div className="plan-chips"><span className="pchip pchip-red">{plan.state}</span><span className="pchip">{plan.branch}</span></div>
      <h3 className="plan-name">{plan.name}</h3>
      <div className="plan-value">{rupees(plan.chitValue)}</div>
      <div className="plan-sub">Total chit value</div>
      <div className="plan-pay"><strong>{rupees(plan.subscription)}</strong>/{freq(plan.frequency)} for {plan.cycles} {freqPlural(plan.frequency)}</div>
      <div className="plan-meta">
        <span>Duration: {plan.cycles} {freqPlural(plan.frequency)}</span>
        <span>{full ? 'Group is full' : `${plan.seatsLeft} seat${plan.seatsLeft === 1 ? '' : 's'} left`}</span>
        <span>Starts {dateOnly(plan.startDate)}</span>
      </div>
      <button className="btn plan-btn" onClick={() => onInvest(plan)} disabled={full || disabled}>{label ?? 'Invest now'}</button>
      </div>
    </article>
  );
}

export function PlanBrowser({ onInvest, joined, heading }: { onInvest: (p: Plan) => void; joined?: Set<string>; heading?: string }) {
  const s = useLoad<Plan[]>(api.plans);
  const [state, setState] = useState('');
  const [cat, setCat] = useState('ALL');
  const rail = useRef<HTMLDivElement>(null);
  const plans = s.data ?? [];
  const states = useMemo(() => Array.from(new Set(plans.map(p => p.state))).sort(), [plans]);
  useEffect(() => { if (!state && states.length > 0) {
      const n = (x: string) => plans.filter(p => p.state === x).length;
      setState([...states].sort((a, b) => n(b) - n(a))[0]);
    } }, [states, state, plans]);
  const inState = plans.filter(p => !state || p.state === state);
  const counts = (k: string) => (k === 'ALL' ? inState.length : inState.filter(p => p.category === k).length);
  const shown = cat === 'ALL' ? inState : inState.filter(p => p.category === cat);
  const scroll = (d: number) => rail.current?.scrollBy({ left: d * 320, behavior: 'smooth' });

  return (
    <section className="deals">
      <div className="deals-head">
        <h2>{heading ?? 'Best deals'}</h2>
        {states.length > 0 && (
          <label className="state-pick">
            <span className="sr">State</span>
            <select value={state} onChange={e => setState(e.target.value)} aria-label="State">
              {states.map(x => <option key={x} value={x}>{x}</option>)}
            </select>
          </label>
        )}
      </div>
      <div className="cat-strip" role="tablist" aria-label="Plan type">
        {CATEGORIES.map(([k, label]) => (
          <button key={k} role="tab" aria-selected={cat === k} className="cat" onClick={() => setCat(k)}>
            <CatIcon k={k} /><span>{label}</span>{counts(k) > 0 && k !== 'ALL' && <em>{counts(k)}</em>}
          </button>
        ))}
      </div>
      {s.loading && <p className="muted" role="status">Loading plans...</p>}
      {s.error && <p className="error">{s.error}</p>}
      {!s.loading && !s.error && shown.length === 0 && (
        <div className="card narrow" style={{ textAlign: 'center' }}>
          <h3 style={{ margin: '0 0 6px' }}>No plans open here right now</h3>
          <p className="muted" style={{ margin: 0 }}>Try another plan type, or check back soon. New groups open every few weeks.</p>
        </div>
      )}
      {shown.length > 0 && (
        <div className="rail-wrap">
          <button className="rail-btn rail-l" onClick={() => scroll(-1)} aria-label="Previous plans"><Icon name="arrow" size={20} /></button>
          <div className="rail" ref={rail}>
            {shown.map(p => <PlanCard key={p.groupCode} plan={p} onInvest={onInvest} disabled={joined?.has(p.groupCode)} label={joined?.has(p.groupCode) ? 'Request sent' : undefined} />)}
          </div>
          <button className="rail-btn rail-r" onClick={() => scroll(1)} aria-label="Next plans"><Icon name="arrow" size={20} /></button>
        </div>
      )}
      <p className="small muted deals-note">Sending a request does not take a seat. Our team reviews it and contacts you before anything is charged.</p>
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
  const [done, setDone] = useState<string | null>(null);
  const key = useRef(`JOIN-${Math.random().toString(36).slice(2)}-${Date.now()}`);
  const live = new Set((reqs.data ?? []).filter(r => r.status === 'PENDING' || r.status === 'APPROVED').map(r => r.groupCode));

  useEffect(() => {
    if (initialJoin && plans.data && !pick) {
      const p = plans.data.find(x => x.groupCode === initialJoin);
      if (p && !live.has(p.groupCode)) setPick(p);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialJoin, plans.data]);

  async function send() {
    if (!pick) return;
    setBusy(true); setErr(null);
    try {
      await api.requestJoin({ groupCode: pick.groupCode, tickets }, key.current + pick.groupCode);
      setDone(pick.name); setPick(null); setTickets(1); reqs.reload();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  return (
    <>
      <div className="page-head"><h1>Choose a plan</h1><p>Pick a chit that fits your goal. Send a request and our team will confirm your seat.</p></div>
      {done && <div className="card note" role="status"><strong>Request sent for {done}.</strong> We will review it and get in touch. You can follow it below.</div>}
      <PlanBrowser heading="Open plans" onInvest={p => { setDone(null); setErr(null); setPick(p); }} joined={live} />
      {pick && (
        <div className="modal-back" role="dialog" aria-modal="true" aria-label="Confirm request">
          <div className="modal">
            <h2 style={{ margin: '0 0 4px' }}>Request to join</h2>
            <p className="muted" style={{ margin: '0 0 12px' }}>{pick.name} ({pick.branch}, {pick.state})</p>
            <dl className="facts">
              <div><dt>Chit value</dt><dd>{rupees(pick.chitValue)}</dd></div>
              <div><dt>You pay</dt><dd>{rupees(pick.subscription)} per {freq(pick.frequency)} per ticket</dd></div>
              <div><dt>Duration</dt><dd>{pick.cycles} {freqPlural(pick.frequency)}</dd></div>
            </dl>
            <label className="field"><span>How many tickets?</span>
              <select value={tickets} onChange={e => setTickets(Number(e.target.value))}>
                {Array.from({ length: Math.max(1, Math.min(3, pick.seatsLeft)) }, (_, i) => i + 1).map(n => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
            <p className="note small" style={{ marginTop: 12 }}>This is a request, not a booking. Nothing is charged now. Please read the group's terms with our team before you pay anything.</p>
            {err && <p className="error" role="alert">{err}</p>}
            <div className="actions">
              <button className="btn" onClick={send} disabled={busy}>{busy ? 'Sending...' : 'Send request'}</button>
              <button className="btn btn-ghost" onClick={() => setPick(null)} disabled={busy}>Cancel</button>
            </div>
          </div>
        </div>
      )}
      <section style={{ marginTop: 26 }}>
        <h2>My join requests</h2>
        {reqs.data && reqs.data.length === 0 && <p className="muted">You have not sent any requests yet.</p>}
        {reqs.data && reqs.data.length > 0 && (
          <div className="card" style={{ padding: '0 16px' }}>
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
    </>
  );
}
