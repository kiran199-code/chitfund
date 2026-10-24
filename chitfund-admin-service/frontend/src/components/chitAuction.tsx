import { Link } from 'react-router-dom';
import { get } from '../api';
import { Tone, date, dateTime, label, money } from '../format';
import { useLoad } from '../hooks';
import { ChitBadge } from './chit';
import DataTable, { Col } from './DataTable';
import { Badge, Card, ErrorBox, KeyValues, Loading } from './ui';
import type { ChitCover, ChitDividend, ChitDraw, ChitPayout, ChitPoolPosition } from '../types';

/** Words for how an auction was decided, as the person at the counter would say them. */
export const BASIS: Record<string, string> = {
  HIGHEST_BID: 'Highest bid',
  TIE_BREAK_EARLIEST: 'Tie on the highest bid: the earliest bid won',
  TIE_BREAK_LOWEST_NO: 'Tie on the highest bid: the lowest ticket number won',
  TIE_BREAK_DRAW: 'Tie on the highest bid: decided by a recorded draw',
  NO_BID_LOTTERY: 'No bids: a recorded draw at the lowest discount',
  NO_BID_FOREMAN_DEFAULT: 'No bids: a recorded draw at the foreman\'s default discount',
  LOTTERY: 'Lottery: a recorded draw',
  FIXED_ORDER: 'Fixed order drawn once for the whole group',
  LAST_CYCLE: 'Last cycle: the one ticket left takes the whole pot',
  ROLLED_OVER: 'No bids: the auction was rolled over to a later date',
};

export const RULE_TEXT: Record<string, string> = {
  ALL_PAID: 'Every instalment of the cycle must be paid',
  MIN_COLLECTION_PCT: 'A minimum share of the cycle must be collected',
  FOREMAN_COVERS_SHORTFALL: 'The foreman covers what is unpaid',
  BLOCK_UNTIL_FUNDED: 'Do not pay until the pool can pay in full',
  FOREMAN_FLOATS: 'The foreman floats the gap and is repaid from later collections',
  PAY_AVAILABLE_AND_BALANCE_LATER: 'Pay what the pool can free now, the balance when it can',
};

export function payoutTone(status: string): Tone {
  switch (status) {
    case 'DISBURSED': return 'good';
    case 'APPROVED': return 'info';
    case 'PENDING_APPROVAL': case 'PART_PAID': return 'warn';
    case 'REJECTED': return 'bad';
    default: return 'neutral';
  }
}
export function PayoutBadge({ status }: { status: string }) {
  return <Badge tone={payoutTone(status)}>{label(status)}</Badge>;
}

/** A recorded draw: everything needed to run it again and get the same answer. */
export function DrawBox({ draw }: { draw: ChitDraw }) {
  return (
    <KeyValues items={[
      ['Purpose', label(draw.purpose)], ['Method', draw.algorithm], ['Seed', <span key="s" className="mono">{draw.seed}</span>],
      ['Tickets in the draw', draw.candidates.join(', ')],
      [draw.purpose === 'FIXED_ORDER' ? 'Order drawn' : 'Result', <strong key="r">{draw.result.join(', ')}</strong>],
      ['Drawn', `${dateTime(draw.createdAt)} by ${draw.createdBy}`],
    ]} />
  );
}

export function DividendsTable({ rows, showTicket = true }: { rows: ChitDividend[]; showTicket?: boolean }) {
  const cols: Col<ChitDividend>[] = [
    { key: 'cy', header: 'Cycle', align: 'right', render: d => d.cycleNo },
    ...(showTicket ? [{ key: 't', header: 'Ticket', align: 'right', render: (d: ChitDividend) => d.ticketNo } as Col<ChitDividend>] : []),
    { key: 'amt', header: 'Dividend', align: 'right', render: d => money(d.amount) },
    { key: 'mode', header: 'How it is given', render: d => label(d.mode) },
    { key: 'st', header: 'Status', render: d => <ChitBadge status={d.status} /> },
    { key: 'on', header: 'Settled on', render: d => (d.settledOn ? date(d.settledOn) : '-') },
  ];
  return <DataTable columns={cols} rows={rows} rowKey={d => `${d.cycleNo}|${d.ticketNo}`} empty="No dividends" />;
}

/** What the pool holds and owes, and what a payout would do under each rule. */
export function PoolPositionBox({ p, rule }: { p: ChitPoolPosition; rule?: string }) {
  return (
    <>
      <KeyValues items={[
        ['Cash in the pool', money(p.poolCash)], ['Owed as commission', money(p.commissionIncome)], ['Owed as dividends', money(p.dividendPayable)],
        ['Late fees held', money(p.lateFeeIncome)], ['Advance held for tickets', money(p.advanceHeld)], ['Prizes owed', money(p.prizePayable)],
        ['Free to pay a prize', <strong key="a">{money(p.availableForPrize)}</strong>],
        ['Left over after every claim', <strong key="s" style={{ color: p.surplus < 0 ? 'var(--bad)' : undefined }}>{money(p.surplus)}</strong>],
      ]} />
      {p.outcomes.length > 0 && (
        <DataTable columns={[
          { key: 'r', header: 'If the prize were paid now under', render: o => <>{label(o.rule)}{o.configured ? <> <Badge tone="info">this group</Badge></> : null}</> },
          { key: 'a', header: 'What happens', render: o => label(o.action) },
          { key: 'n', header: 'Paid now', align: 'right', render: o => money(o.payNow) },
          { key: 'f', header: 'Foreman floats', align: 'right', render: o => (o.floatAmount > 0 ? money(o.floatAmount) : '-') },
          { key: 'b', header: 'Paid later', align: 'right', render: o => (o.balanceLater > 0 ? money(o.balanceLater) : '-') },
          { key: 's', header: 'Short by', align: 'right', render: o => (o.shortfall > 0 ? money(o.shortfall) : '-') },
        ]} rows={p.outcomes} rowKey={o => o.rule} />
      )}
      {rule && <p className="muted">This group's rule: {RULE_TEXT[rule] ?? label(rule)}.</p>}
    </>
  );
}

/** The group page: prizes by cycle, the pool position, and the foreman's covers. */
export function GroupPrizesCard({ code, refresh }: { code: string; refresh: number }) {
  const base = `/chit/groups/${encodeURIComponent(code)}`;
  const payouts = useLoad(() => get<ChitPayout[]>(`${base}/payouts`), [code, refresh]);
  const pool = useLoad(() => get<ChitPoolPosition>(`${base}/liquidity`), [code, refresh]);
  const covers = useLoad(() => get<ChitCover[]>(`${base}/foreman-covers`), [code, refresh]);
  const dividends = useLoad(() => get<ChitDividend[]>(`${base}/dividends`), [code, refresh]);

  const payoutCols: Col<ChitPayout>[] = [
    { key: 'c', header: 'Cycle', render: p => <Link to={`${base}/cycles/${p.cycleNo}`}>{p.cycleNo}</Link> },
    { key: 't', header: 'Winner', render: p => <Link to={`${base}/tickets/${p.ticketNo}`}>#{p.ticketNo}</Link> },
    { key: 'g', header: 'Prize', align: 'right', render: p => money(p.grossPrize) },
    { key: 'h', header: 'Held back', align: 'right', render: p => (p.holdAmount > 0 ? money(p.holdAmount) : '-') },
    { key: 'p', header: 'Paid so far', align: 'right', render: p => money(p.paidGross) },
    { key: 'st', header: 'Status', render: p => <PayoutBadge status={p.status} /> },
    { key: 'mc', header: 'Requested / approved by', render: p => `${p.madeBy}${p.checkedBy ? ` / ${p.checkedBy}` : ''}` },
  ];

  return (
    <Card title="Prizes, dividends and the pool">
      {payouts.loading && !payouts.data && <Loading what="Reading the prizes" />}
      {payouts.error && <ErrorBox message={payouts.error} onRetry={payouts.reload} />}
      {payouts.data && <DataTable columns={payoutCols} rows={payouts.data} rowKey={p => p.cycleNo} empty="No prize has been requested yet. Open a cycle from the table above to run its auction." />}
      {pool.data && (
        <>
          <h3 style={{ marginBottom: 6 }}>The pool now</h3>
          <PoolPositionBox p={pool.data} />
        </>
      )}
      {covers.data && covers.data.length > 0 && (
        <>
          <h3 style={{ marginBottom: 6 }}>Instalments the foreman covered</h3>
          <DataTable columns={[
            { key: 'c', header: 'Cycle', align: 'right', render: c => c.cycleNo },
            { key: 't', header: 'Ticket', align: 'right', render: c => c.ticketNo },
            { key: 'a', header: 'Covered', align: 'right', render: c => money(c.amount) },
            { key: 'r', header: 'Recovered', align: 'right', render: c => money(c.recovered) },
            { key: 's', header: 'Status', render: c => <ChitBadge status={c.status} /> },
            { key: 'on', header: 'On', render: c => date(c.coveredOn) },
          ]} rows={covers.data} rowKey={c => `${c.cycleNo}|${c.ticketNo}`} />
          <p className="muted">The ticket owes the foreman these amounts; recovering them from the ticket is handled in a later phase.</p>
        </>
      )}
      {dividends.data && dividends.data.length > 0 && (
        <details>
          <summary>All dividends ({dividends.data.length})</summary>
          <DividendsTable rows={dividends.data} />
        </details>
      )}
    </Card>
  );
}

/** The dividends one ticket has been given, on its account page. */
export function TicketDividendsCard({ code, ticketNo, refresh }: { code: string; ticketNo: number; refresh: number }) {
  const { data, error, loading, reload } = useLoad(
    () => get<ChitDividend[]>(`/chit/groups/${encodeURIComponent(code)}/dividends`, { ticketNo }), [code, ticketNo, refresh]);
  if (loading && !data) return null;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data || data.length === 0) return null;
  return (
    <Card title={`Dividends (${data.length})`}>
      <DividendsTable rows={data} showTicket={false} />
    </Card>
  );
}
