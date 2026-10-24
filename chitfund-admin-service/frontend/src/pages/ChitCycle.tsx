import { ReactNode, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, get, send } from '../api';
import { ChitBadge, ChitGate, useCanChangeChit } from '../components/chit';
import { BASIS, DividendsTable, DrawBox, PayoutBadge, PoolPositionBox, RULE_TEXT } from '../components/chitAuction';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, NoticeBanner, useActingAs } from '../components/actingAs';
import { ConfirmModal, Field, Notice, useIdempotencyKey } from '../components/ops';
import { Card, ErrorBox, KeyValues, Kpi, Loading, PageHeader } from '../components/ui';
import { date, dateTime, label, money } from '../format';
import { useLoad } from '../hooks';
import type {
  ChitAuction, ChitBid, ChitDividend, ChitDividendPayResult, ChitGate as Gate, ChitPayout, ChitPoolPosition,
} from '../types';

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

type Dialog =
  | { kind: 'open' }
  | { kind: 'bid' }
  | { kind: 'withdraw'; bid: ChitBid }
  | { kind: 'close' }
  | { kind: 'request' }
  | { kind: 'approve' }
  | { kind: 'reject' }
  | { kind: 'disburse' }
  | { kind: 'float' }
  | { kind: 'dividends' }
  | null;

export default function ChitCycle() {
  const { code = '', no = '' } = useParams();
  const n = Number(no);
  return (
    <>
      <PageHeader title={<>Cycle <span className="mono">{code} / {no}</span></>}
        sub={<><Link to={`/chit/groups/${encodeURIComponent(code)}`}>Group {code}</Link>
          {n > 1 && <> · <Link to={`/chit/groups/${encodeURIComponent(code)}/cycles/${n - 1}`}>Cycle {n - 1}</Link></>}
          {Number.isInteger(n) && n >= 1 && <> · <Link to={`/chit/groups/${encodeURIComponent(code)}/cycles/${n + 1}`}>Cycle {n + 1}</Link></>}</>} />
      <ChitGate><Body code={code} cycleNo={n} /></ChitGate>
    </>
  );
}

/** A payout that does not exist yet answers 404; that is not an error on this page. */
async function getOrNull<T>(path: string): Promise<T | null> {
  try {
    return await get<T>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

function Body({ code, cycleNo }: { code: string; cycleNo: number }) {
  const canChange = useCanChangeChit();
  const actor = useActingAs();
  const [tick, setTick] = useState(0);
  const [notice, setNotice] = useState<Notice>(null);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [ticketText, setTicketText] = useState('');
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState('');
  const keys = useIdempotencyKey();
  const base = `/chit/groups/${encodeURIComponent(code)}/cycles/${cycleNo}`;

  const auction = useLoad(() => get<ChitAuction>(`${base}/auction`), [code, cycleNo, tick]);
  const a = auction.data;
  const preAuction = !!a && a.status === 'NOT_OPENED';
  const gate = useLoad<Gate | null>(() => (preAuction ? get<Gate>(`${base}/auction/gate`) : Promise.resolve(null)), [code, cycleNo, tick, a?.status]);
  const closed = !!a && a.status === 'CLOSED';
  const payout = useLoad<ChitPayout | null>(() => (closed ? getOrNull<ChitPayout>(`${base}/payout`) : Promise.resolve(null)), [code, cycleNo, tick, a?.status]);
  const pool = useLoad<ChitPoolPosition | null>(
    () => (closed ? get<ChitPoolPosition>(`/chit/groups/${encodeURIComponent(code)}/liquidity`, { cycleNo }) : Promise.resolve(null)),
    [code, cycleNo, tick, a?.status, payout.data?.status]);
  const dividends = useLoad<ChitDividend[]>(
    () => (closed ? get<ChitDividend[]>(`/chit/groups/${encodeURIComponent(code)}/dividends`).then(rows => rows.filter(d => d.cycleNo === cycleNo)) : Promise.resolve([])),
    [code, cycleNo, tick, a?.status, payout.data?.status]);

  function refresh() { setTick(t => t + 1); }
  function done(text: string) {
    setNotice({ tone: 'good', text });
    setDialog(null);
    setTicketText('');
    setAmountText('');
    setReason('');
    refresh();
  }
  /** One state-changing call; the same request keeps its Idempotency-Key until it succeeds, so a retry is applied once. */
  async function post<T>(sub: string, body?: unknown): Promise<T> {
    const r = await send<T>('POST', `${base}${sub}`, body, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|${sub}|${JSON.stringify(body ?? null)}`) });
    keys.done();
    return r;
  }
  const replayed = (r: { replayed?: boolean | null }) => (r.replayed ? ' (the same request had already been applied; nothing new was done)' : '');

  if (!Number.isInteger(cycleNo) || cycleNo < 1) return <ErrorBox message="That is not a cycle number" />;
  if (auction.loading && !a) return <Loading what="Loading the cycle (the chit service may be waking up)" />;
  if (auction.error) return <ErrorBox message={auction.error} onRetry={auction.reload} />;
  if (!a) return null;

  const p = payout.data;
  const g = gate.data;
  const isOpen = a.status === 'OPEN';
  const drawn = a.auctionType === 'LOTTERY' || a.auctionType === 'FIXED_ORDER';
  const takesBids = isOpen && !drawn && !a.lastCycle;
  const validBids = a.bids.filter(b => b.status === 'VALID').length;
  const unpaidDividends = (dividends.data ?? []).filter(d => d.status === 'DECLARED' && d.mode === 'PAY_OUT');
  const myConfigured = pool.data?.outcomes.find(o => o.configured);
  const floatOwed = p ? p.floatAmount - p.floatRepaid : 0;

  const bidCols: Col<ChitBid>[] = [
    { key: 'seq', header: '#', align: 'right', render: b => b.bidSeq },
    { key: 't', header: 'Ticket', align: 'right', render: b => <strong>{b.ticketNo}</strong> },
    { key: 'amt', header: 'Discount offered', align: 'right', render: b => (b.discountAmount === null || b.discountAmount === undefined ? <span className="muted">sealed</span> : money(b.discountAmount)) },
    { key: 'time', header: 'Entered', render: b => dateTime(b.bidTime) },
    { key: 'by', header: 'By', render: b => b.placedBy },
    { key: 'st', header: 'Status', render: b => (
      <>{b.winning ? <ChitBadge status="PRIZED" /> : <ChitBadge status={b.status} />}{b.withdrawnReason ? <div className="muted">{b.withdrawnReason}</div> : null}</>) },
    { key: 'act', header: '', render: b => (canChange && isOpen && b.status === 'VALID'
      ? <button className="btn" onClick={() => { setReason(''); setDialog({ kind: 'withdraw', bid: b }); }}>Withdraw</button> : null) },
  ];

  const statusLine: ReactNode = (
    <>Cycle status <ChitBadge status={a.cycleStatus} /> · instalments due {date(a.dueDate)} · auction on {date(a.auctionDate)}
      {a.rolledOverAttempts && a.rolledOverAttempts.length > 0 ? <> · rolled over {a.rolledOverAttempts.length} time{a.rolledOverAttempts.length === 1 ? '' : 's'}</> : null}</>
  );

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={notice} />
      <p className="muted" style={{ marginTop: 0 }}>{statusLine}</p>

      {/* ---------------------------------------------------------------- before the auction */}
      {preAuction && (
        <Card title="Can the auction open?"
          actions={canChange && g?.open && g.due ? <button className="btn btn-primary" onClick={() => setDialog({ kind: 'open' })}>Open the auction</button> : undefined}>
          {gate.loading && !g && <Loading what="Checking the gate" />}
          {gate.error && <ErrorBox message={gate.error} onRetry={gate.reload} />}
          {g && (
            <>
              <div className={`banner ${g.open && g.due ? 'banner-good' : 'banner-warn'}`} role="status">{g.reason}</div>
              <KeyValues items={[
                ['Rule', `${label(g.rule)}: ${RULE_TEXT[g.rule] ?? ''}`],
                ['Auction date', date(g.auctionDate)],
                ...(g.dueAmount !== null && g.dueAmount !== undefined ? [
                  ['Due this cycle', money(g.dueAmount)], ['Collected', `${money(g.collected)} (${Number(g.collectedPct ?? 0).toFixed(1)}%)`],
                  ['Unpaid', money(g.shortfall)]] as [string, ReactNode][] : []),
                ...(g.lastCycle ? [['Last cycle', 'The one ticket left takes the whole pot; nobody bids.'] as [string, ReactNode]] : []),
              ]} />
              {g.unpaid && g.unpaid.length > 0 && (
                <DataTable columns={[
                  { key: 't', header: 'Ticket with an unpaid instalment', render: u => <Link to={`/chit/groups/${encodeURIComponent(code)}/tickets/${u.ticketNo}`}>#{u.ticketNo}</Link> },
                  { key: 'a', header: 'Unpaid', align: 'right', render: u => money(u.amount) },
                ]} rows={g.unpaid} rowKey={u => u.ticketNo} />
              )}
              {g.rule === 'FOREMAN_COVERS_SHORTFALL' && g.open && (
                <p className="muted">Opening the auction makes the foreman pay each unpaid instalment into the pool. The ticket then owes that amount to the foreman.</p>
              )}
            </>
          )}
        </Card>
      )}

      {/* ---------------------------------------------------------------- the auction */}
      {!preAuction && (
        <Card title={<>Auction <ChitBadge status={a.status} />{a.attempt && a.attempt > 1 ? <span className="muted"> · attempt {a.attempt}</span> : null}</>}
          actions={canChange && isOpen ? (
            <>
              {takesBids && <button className="btn" onClick={() => { setTicketText(''); setAmountText(''); setDialog({ kind: 'bid' }); }}>Enter a bid</button>}
              <button className="btn btn-primary" onClick={() => setDialog({ kind: 'close' })}>Close the auction</button>
            </>) : undefined}>
          <KeyValues items={[
            ['Type', label(a.auctionType)],
            ['Discount allowed', `${money(a.minDiscount)} to ${money(a.maxDiscount)}`],
            ...(a.openedAt ? [['Opened', `${dateTime(a.openedAt)} by ${a.openedBy ?? '-'}`] as [string, ReactNode]] : []),
            ...(a.gateNote ? [['At opening', a.gateNote] as [string, ReactNode]] : []),
            ...(takesBids ? [['Bids so far', a.bidsHidden ? `${a.bidCount} (sealed: amounts stay hidden until the auction closes)` : `${a.bidCount}${a.highestBid ? `, highest ${money(a.highestBid)}` : ''}`] as [string, ReactNode]] : []),
            ...(a.closedAt ? [['Closed', `${dateTime(a.closedAt)} by ${a.closedBy ?? '-'}`] as [string, ReactNode]] : []),
          ]} />
          {isOpen && drawn && (
            <p className="muted">This group's auction type is {label(a.auctionType)}: no bids are taken. Closing draws the winner and records the draw.</p>
          )}
          {isOpen && a.lastCycle && (
            <p className="muted">This is the last cycle. Closing gives the whole pot to the one ticket that has not yet won.</p>
          )}
          {isOpen && takesBids && a.auctionType === 'OPEN_ASCENDING_DISCOUNT' && (
            <p className="muted">Each bid must beat the highest so far. Bids are entered here by the person at the counter on the holder's behalf.</p>
          )}
          {a.bids.length > 0 && <DataTable columns={bidCols} rows={a.bids} rowKey={b => b.bidSeq} />}
          {isOpen && takesBids && validBids === 0 && <p className="muted">No bid yet. If the auction closes without one, the group's no-bid rule applies.</p>}

          {closed && a.settlement && (
            <>
              <div className="banner banner-good" role="status" style={{ marginTop: 12 }}>
                Ticket <strong>{a.winnerTicketNo}</strong> (customer {a.winnerCustomerId}) wins {money(a.settlement.prize)}. {a.closeBasis ? BASIS[a.closeBasis] ?? label(a.closeBasis) : ''}
              </div>
              <div className="kpis">
                <Kpi title="Chit value" value={money(a.settlement.chitValue)} />
                <Kpi title="Discount" value={money(a.settlement.discount)} />
                <Kpi title="Prize to the winner" value={money(a.settlement.prize)} tone="good" />
                <Kpi title="Foreman's commission" value={money(a.settlement.commission)} />
                <Kpi title="Shared as dividends" value={money(a.settlement.dividendPool)} sub={`${a.settlement.dividends.length} ticket${a.settlement.dividends.length === 1 ? '' : 's'}`} />
              </div>
              {!a.settlement.balanced && <ErrorBox message="The parts do not add up to the chit value. Do not pay this prize; tell the platform team." />}
            </>
          )}
          {a.draw && (
            <>
              <h3 style={{ marginBottom: 6 }}>The draw</h3>
              <DrawBox draw={a.draw} />
              <p className="muted">The same seed and tickets always give the same result, so the draw can be re-run and checked.</p>
            </>
          )}
        </Card>
      )}

      {/* ---------------------------------------------------------------- the prize */}
      {closed && (
        <Card title={<>Prize payout {p ? <PayoutBadge status={p.status} /> : null}</>}
          actions={canChange ? (
            <>
              {!p && <button className="btn btn-primary" onClick={() => setDialog({ kind: 'request' })}>Request the payout</button>}
              {p?.status === 'REJECTED' && <button className="btn btn-primary" onClick={() => setDialog({ kind: 'request' })}>Request again</button>}
              {p?.status === 'PENDING_APPROVAL' && (
                <>
                  <button className="btn btn-primary" onClick={() => setDialog({ kind: 'approve' })}>Approve</button>{' '}
                  <button className="btn btn-danger" onClick={() => { setReason(''); setDialog({ kind: 'reject' }); }}>Reject</button>
                </>)}
              {(p?.status === 'APPROVED' || p?.status === 'PART_PAID') && (
                <button className="btn btn-primary" onClick={() => setDialog({ kind: 'disburse' })}>{p.status === 'PART_PAID' ? 'Pay the balance' : 'Pay out'}</button>)}
              {p && floatOwed > 0 && <button className="btn" onClick={() => { setAmountText(floatOwed.toFixed(2)); setDialog({ kind: 'float' }); }}>Repay the foreman's float</button>}
            </>) : undefined}>
          {payout.loading && !payout.data && <Loading />}
          {payout.error && <ErrorBox message={payout.error} onRetry={payout.reload} />}
          {!p && !payout.loading && (
            <p>No payout has been requested. One person requests it and a different person approves it before any money moves. The request checks that the
              winner's security is verified, nothing is overdue and a payout account is on record.</p>
          )}
          {p && (
            <>
              <KeyValues items={[
                ['Winner', `Ticket ${p.ticketNo} (customer ${p.customerId})`], ['Payout account', p.accountRef ?? '-'],
                ['Prize', money(p.grossPrize)], ['Held back as security', p.holdAmount > 0 ? money(p.holdAmount) : 'None'], ['Net to the winner', money(p.netAmount)],
                ['Requested by', p.madeBy], ['Decided by', p.checkedBy ? `${p.checkedBy}${p.approvedAt ? ` on ${dateTime(p.approvedAt)}` : ''}` : 'Not yet'],
                ...(p.decisionNote ? [['Reason given', p.decisionNote] as [string, ReactNode]] : []),
                ['Paid so far', `${money(p.paidGross)} of ${money(p.grossPrize)}`],
                ...(p.floatAmount > 0 ? [['Foreman floated', `${money(p.floatAmount)}, repaid ${money(p.floatRepaid)}`] as [string, ReactNode]] : []),
                ...(p.payoutRef ? [['Reference', <span key="r" className="mono">{p.payoutRef}</span>] as [string, ReactNode]] : []),
              ]} />
              {p.legs.length > 0 && (
                <DataTable columns={[
                  { key: 'n', header: 'Leg', align: 'right', render: l => l.legNo },
                  { key: 'k', header: 'What', render: l => label(l.kind) },
                  { key: 'a', header: 'Amount', align: 'right', render: l => money(l.amount) },
                  { key: 'h', header: 'Of which held back', align: 'right', render: l => (l.holdAmount > 0 ? money(l.holdAmount) : '-') },
                  { key: 'd', header: 'Date', render: l => date(l.businessDate) },
                  { key: 'by', header: 'By', render: l => l.createdBy },
                  { key: 'ref', header: 'Instruction', render: l => (l.instructionRef ? <span className="mono">{l.instructionRef}</span> : '-') },
                ]} rows={p.legs} rowKey={l => l.legNo} />
              )}
            </>
          )}
          {pool.data && p?.status !== 'DISBURSED' && (
            <>
              <h3 style={{ marginBottom: 6 }}>Can the pool pay it?</h3>
              <PoolPositionBox p={pool.data} rule={myConfigured?.rule} />
            </>
          )}
        </Card>
      )}

      {/* ---------------------------------------------------------------- dividends */}
      {closed && (dividends.data ?? []).length > 0 && (
        <Card title="Dividends of this cycle"
          actions={canChange && unpaidDividends.length > 0 ? <button className="btn" onClick={() => setDialog({ kind: 'dividends' })}>Pay out the dividends</button> : undefined}>
          <DividendsTable rows={dividends.data ?? []} />
          <p className="muted">
            {(dividends.data ?? [])[0]?.mode === 'ADJUST_NEXT_INSTALMENT' && 'Each dividend is set against the ticket\'s next instalment when that instalment is raised.'}
            {(dividends.data ?? [])[0]?.mode === 'CREDIT_SUBSCRIBER_BALANCE' && 'Each dividend was credited to the ticket\'s account when the auction closed, and reduces what it owes.'}
            {(dividends.data ?? [])[0]?.mode === 'PAY_OUT' && 'Dividends are paid out in cash with the button above; the cycle is settled once they are paid.'}
          </p>
        </Card>
      )}

      {/* ---------------------------------------------------------------- dialogs */}
      {dialog?.kind === 'open' && (
        <ConfirmModal title={`Open the auction of cycle ${cycleNo}?`} confirmLabel="Open the auction"
          rows={[['Group', code], ['Cycle', cycleNo], ['Opened by', actor]]}
          note={g?.rule === 'FOREMAN_COVERS_SHORTFALL' && (g.unpaid?.length ?? 0) > 0
            ? `The foreman will pay ${money(g.shortfall)} into the pool for ${g.unpaid?.length} unpaid instalment(s). Those tickets then owe the foreman.` : undefined}
          onConfirm={async () => { const r = await post<ChitAuction>('/auction/open'); done(`Auction of cycle ${cycleNo} is open${replayed(r)}.`); }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'bid' && (
        <ConfirmModal title="Enter a bid" confirmLabel="Record the bid"
          rows={[['Group', code], ['Cycle', cycleNo], ['Entered by', actor], ['Allowed', `${money(a.minDiscount)} to ${money(a.maxDiscount)}`]]}
          note={(
            <>
              <Field label="Ticket number"><input inputMode="numeric" value={ticketText} onChange={e => setTicketText(e.target.value)} autoFocus /></Field>
              <Field label="Discount offered (INR)"
                hint={a.auctionType === 'SEALED_BID' ? 'Sealed: the amount is hidden until the auction closes. A new bid from the same ticket replaces its earlier one.' : a.highestBid ? `Must be more than ${money(a.highestBid)}` : undefined}>
                <input inputMode="decimal" value={amountText} onChange={e => setAmountText(e.target.value)} />
              </Field>
            </>)}
          onConfirm={async () => {
            if (!/^\d+$/.test(ticketText.trim())) throw new Error('Enter the ticket number');
            if (!AMOUNT.test(amountText.trim()) || Number(amountText) <= 0) throw new Error('Enter the discount as a positive amount with at most two decimals');
            const r = await post<ChitBid>('/auction/bids', { ticketNo: Number(ticketText.trim()), discountAmount: Number(amountText.trim()) });
            done(`Bid #${r.bidSeq} for ticket ${r.ticketNo} recorded${replayed(r)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'withdraw' && (
        <ConfirmModal title={`Withdraw bid #${dialog.bid.bidSeq}?`} confirmLabel="Withdraw the bid" danger
          rows={[['Ticket', dialog.bid.ticketNo], ['Amount', dialog.bid.discountAmount === null || dialog.bid.discountAmount === undefined ? 'Sealed' : money(dialog.bid.discountAmount)], ['By', actor]]}
          note={<Field label="Reason (required)"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={120} autoFocus /></Field>}
          onConfirm={async () => {
            if (!reason.trim()) throw new Error('A reason is required');
            const r = await post<ChitBid>(`/auction/bids/${dialog.bid.bidSeq}/withdraw`, { reason: reason.trim() });
            done(`Bid #${r.bidSeq} withdrawn${replayed(r)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'close' && (
        <ConfirmModal title={`Close the auction of cycle ${cycleNo}?`} confirmLabel="Close the auction"
          rows={[['Group', code], ['Cycle', cycleNo], ['Bids', takesBids ? validBids : 'none taken'], ['Closed by', actor]]}
          note="Closing decides the winner, splits the pot into prize, commission and dividends, and posts it to the books. It cannot be undone. If nobody has bid, the group's no-bid rule applies."
          onConfirm={async () => {
            const r = await post<ChitAuction>('/auction/close');
            done(r.status === 'ROLLED_OVER'
              ? `No bids. The auction was rolled over to ${date(r.auctionDate)} and later cycles moved${replayed(r)}.`
              : `Auction closed. Ticket ${r.winnerTicketNo} wins ${money(r.settlement?.prize)}${replayed(r)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'request' && (
        <ConfirmModal title="Request the prize payout" confirmLabel="Request payout"
          rows={[['Winner', `Ticket ${a.winnerTicketNo}`], ['Prize', money(a.settlement?.prize)], ['Requested by', actor]]}
          note="A different person must approve this before any money moves. The request is refused if the winner's security is not verified, anything is overdue, or no payout account is on record."
          onConfirm={async () => { const r = await post<ChitPayout>('/payout/request'); done(`Payout requested${replayed(r)}. Another person can now approve it.`); }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'approve' && p && (
        <ConfirmModal title="Approve the payout?" confirmLabel="Approve"
          rows={[['Winner', `Ticket ${p.ticketNo}`], ['Prize', money(p.grossPrize)], ['Requested by', p.madeBy], ['Approving as', actor]]}
          note={actor === p.madeBy ? 'You requested this payout. The service will refuse your approval: switch "acting as" to a different person.' : undefined}
          onConfirm={async () => { const r = await post<ChitPayout>('/payout/approve'); done(`Payout approved${replayed(r)}.`); }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'reject' && p && (
        <ConfirmModal title="Reject the payout request?" confirmLabel="Reject" danger
          rows={[['Winner', `Ticket ${p.ticketNo}`], ['Prize', money(p.grossPrize)], ['Requested by', p.madeBy], ['Rejecting as', actor]]}
          note={<Field label="Reason (required)"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={200} autoFocus /></Field>}
          onConfirm={async () => {
            if (!reason.trim()) throw new Error('A reason is required');
            const r = await post<ChitPayout>('/payout/reject', { reason: reason.trim() });
            done(`Payout rejected${replayed(r)}. It can be requested again once the cause is fixed.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'disburse' && p && (
        <ConfirmModal title={p.status === 'PART_PAID' ? 'Pay the balance of the prize?' : 'Pay out the prize?'} confirmLabel="Pay out"
          rows={[
            ['Winner', `Ticket ${p.ticketNo} (account ${p.accountRef ?? '-'})`], ['Still to pay', money(p.remainingGross)],
            ...(myConfigured ? [['Rule', label(myConfigured.rule)], ['Pays now', money(myConfigured.payNow)]] as [string, ReactNode][] : []),
            ...(myConfigured && myConfigured.floatAmount > 0 ? [['Foreman floats', money(myConfigured.floatAmount)]] as [string, ReactNode][] : []),
            ...(myConfigured && myConfigured.balanceLater > 0 ? [['Paid later', money(myConfigured.balanceLater)]] as [string, ReactNode][] : []),
            ['Paying as', actor],
          ]}
          note={myConfigured && myConfigured.action === 'BLOCKED'
            ? `The pool cannot pay this prize yet (short by ${money(myConfigured.shortfall)}). The service will refuse until the unpaid instalments are collected.`
            : 'The money is posted out of the pool and the payout instruction is recorded. The winner\'s ticket becomes PRIZED.'}
          onConfirm={async () => {
            const r = await post<ChitPayout>('/payout/disburse');
            done(r.status === 'DISBURSED' ? `Prize paid in full${replayed(r)}.` : `Part of the prize was paid (${money(r.paidGross)} of ${money(r.grossPrize)}); the balance follows when the pool can free it${replayed(r)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'float' && p && (
        <ConfirmModal title="Repay the foreman's float" confirmLabel="Repay"
          rows={[['Still owed to the foreman', money(floatOwed)], ['Paying as', actor]]}
          note={(
            <>
              <Field label="Amount (INR)" hint={pool.data ? `The pool has ${money(pool.data.surplus)} beyond what it owes` : undefined}>
                <input inputMode="decimal" value={amountText} onChange={e => setAmountText(e.target.value)} autoFocus />
              </Field>
            </>)}
          onConfirm={async () => {
            if (!AMOUNT.test(amountText.trim()) || Number(amountText) <= 0) throw new Error('Enter a positive amount with at most two decimals');
            const r = await post<ChitPayout>('/payout/float-repayments', { amount: Number(amountText.trim()) });
            done(`Float repaid: ${money(r.floatRepaid)} of ${money(r.floatAmount)}${replayed(r)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'dividends' && (
        <ConfirmModal title="Pay out the dividends?" confirmLabel="Pay out"
          rows={[['Tickets', unpaidDividends.length], ['Total', money(unpaidDividends.reduce((s, d) => s + d.amount, 0))], ['Paying as', actor]]}
          note="Each dividend is paid from the pool's cash. The service refuses if the pool does not hold enough beyond what it owes."
          onConfirm={async () => { const r = await post<ChitDividendPayResult>('/dividends/pay'); done(`${r.paid} dividend(s) paid, ${money(r.total)} in all${replayed(r)}.`); }}
          onClose={() => setDialog(null)} />
      )}
    </>
  );
}
