import { useState } from 'react';
import { get, send } from '../api';
import { ConfirmModal, Field, Notice, useIdempotencyKey } from './ops';
import { Badge, Card, ErrorBox, Loading } from './ui';
import DataTable, { Col } from './DataTable';
import { useActingAs } from './actingAs';
import { dateTime, label, money, Tone } from '../format';
import { useLoad } from '../hooks';
import { wholeNumber } from './chit';
import type { ChitQuote, PortalBatchResult, PortalInvitation } from '../types';

const TONE: Record<string, Tone> = {
  PENDING: 'info', ACCEPTED_AWAITING_PAYMENT: 'warn', ACCEPTED: 'good', DECLINED: 'neutral', EXPIRED: 'neutral', REVOKED: 'neutral',
};

export interface InviteLine { email: string; name?: string; tickets?: number; existingCustomerId?: number }

/**
 * One person per line: `email[, name[, tickets[, old customer id]]]`. Lines may also be separated by semicolons. Returns the people and the
 * lines that could not be read (the server validates the addresses themselves).
 */
export function parseInviteLines(text: string): { people: InviteLine[]; problems: string[] } {
  const people: InviteLine[] = [];
  const problems: string[] = [];
  text.split(/[\n;]+/).map(l => l.trim()).filter(Boolean).forEach(line => {
    const [email, name, tickets, existing] = line.split(',').map(x => x.trim());
    if (!email || !email.includes('@')) { problems.push(`"${line}": no e-mail address`); return; }
    const p: InviteLine = { email };
    if (name) p.name = name;
    if (tickets) {
      if (!wholeNumber(tickets) || Number(tickets) < 1) { problems.push(`"${line}": tickets must be a whole number from 1`); return; }
      p.tickets = Number(tickets);
    }
    if (existing) {
      if (!wholeNumber(existing) || Number(existing) < 1) { problems.push(`"${line}": the old customer id must be a whole number`); return; }
      p.existingCustomerId = Number(existing);
    }
    people.push(p);
  });
  return { people, problems };
}

/** Invite several people to a group by e-mail, follow their replies, and record the receipt that enrols an accepted invitation. */
export function InvitePanel({ code, quote, onChanged }: { code: string; quote: ChitQuote | null; onChanged: () => void }) {
  const actor = useActingAs();
  const keys = useIdempotencyKey();
  const base = `/portal/groups/${encodeURIComponent(code)}/invitations`;
  const status = useLoad(() => get<{ enabled: boolean }>('/portal/status'), []);
  const [tick, setTick] = useState(0);
  const list = useLoad<PortalInvitation[]>(() => (status.data?.enabled ? get<PortalInvitation[]>(base) : Promise.resolve([])), [code, tick, status.data?.enabled]);
  const [text, setText] = useState('');
  const [days, setDays] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const [result, setResult] = useState<PortalBatchResult | null>(null);
  const [revoking, setRevoking] = useState<PortalInvitation | null>(null);
  const [enrolling, setEnrolling] = useState<PortalInvitation | null>(null);
  const [reason, setReason] = useState('');

  function refresh() { setTick(t => t + 1); onChanged(); }

  async function sendInvites() {
    const { people, problems } = parseInviteLines(text);
    if (problems.length) { setNotice({ tone: 'bad', text: problems.join('; ') }); return; }
    if (people.length === 0) { setNotice({ tone: 'bad', text: 'Add at least one e-mail address.' }); return; }
    if (days.trim() && (!wholeNumber(days) || Number(days) < 1 || Number(days) > 30)) { setNotice({ tone: 'bad', text: 'Expiry must be 1 to 30 days.' }); return; }
    const body: Record<string, unknown> = { invitees: people };
    if (days.trim()) body.expiryDays = Number(days);
    setBusy(true);
    setNotice(null);
    try {
      const r = await send<PortalBatchResult>('POST', base, body, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|invite|${JSON.stringify(body)}`) });
      keys.done();
      setResult(r);
      setNotice({ tone: r.created.length > 0 ? 'good' : 'warn',
        text: `${r.created.length} invitation(s) queued by e-mail${r.skipped.length ? `, ${r.skipped.length} skipped (see below)` : ''}.` });
      if (r.skipped.length === 0) setText('');
      refresh();
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  async function resend(i: PortalInvitation) {
    setNotice(null);
    try {
      await send('POST', `${base}/${i.id}/resend`, undefined, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|resend|${i.id}`) });
      keys.done();
      setNotice({ tone: 'good', text: `A new invitation link was queued for ${i.email}.` });
      refresh();
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    }
  }

  const cols: Col<PortalInvitation>[] = [
    { key: 'email', header: 'E-mail', render: i => <>{i.email}{i.inviteeName ? <div className="muted">{i.inviteeName}</div> : null}</> },
    { key: 'cust', header: 'Customer id', render: i => i.customerId ?? (i.existingCustomerId ? <span className="muted">{i.existingCustomerId} (to link)</span> : <span className="muted">new on accept</span>) },
    { key: 'status', header: 'Status', render: i => <Badge tone={TONE[i.status] ?? 'neutral'}>{label(i.status)}</Badge> },
    { key: 'tickets', header: 'Tickets', render: i => `${i.ticketsEnrolled} of ${i.tickets}` },
    { key: 'mail', header: 'Mail', render: i => (i.mailStatus === 'SEND_FAILED'
        ? <span className="error">Failed</span> : i.mailStatus === 'SENT' ? (i.firstOpenedAt ? 'Opened' : 'Sent') : i.mailStatus ? 'Waiting' : '-') },
    { key: 'exp', header: 'Reply by', render: i => dateTime(i.expiresAt) },
    {
      key: 'act', header: '', render: i => (
        <span style={{ whiteSpace: 'nowrap' }}>
          {i.status === 'ACCEPTED_AWAITING_PAYMENT' || (i.status === 'ACCEPTED' && i.ticketsEnrolled < i.tickets)
            ? <button className="btn btn-primary" onClick={() => setEnrolling(i)} disabled={!quote}>{i.status === 'ACCEPTED' ? 'Enrol the rest' : 'Record fees and enrol'}</button> : null}{' '}
          {i.status === 'PENDING' || i.status === 'EXPIRED' ? <button className="btn" onClick={() => resend(i)}>{i.status === 'EXPIRED' ? 'Invite again' : 'Resend'}</button> : null}{' '}
          {i.status === 'PENDING' || i.status === 'ACCEPTED_AWAITING_PAYMENT' ? <button className="btn" onClick={() => { setReason(''); setRevoking(i); }}>Withdraw</button> : null}
        </span>),
    },
  ];

  return (
    <Card title="Invite by e-mail" actions={status.data?.enabled ? <button className="btn" onClick={() => setTick(t => t + 1)}>Refresh</button> : undefined}>
      {status.loading && !status.data && <Loading what="Checking the customer portal" />}
      {status.error && <ErrorBox message={status.error} onRetry={status.reload} />}
      {status.data && !status.data.enabled && (
        <div className="banner banner-warn" role="alert">
          Invitations are not set up. Set PORTAL_SERVICE_URL (the public address of chitfund-portal-service) and PORTAL_ADMIN_PASSWORD on
          chitfund-admin-service and restart it.
        </div>
      )}
      {status.data?.enabled && (
        <>
          {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
          <Field wide label="People to invite" hint="One person per line: e-mail, name (optional), tickets (optional, default 1), old customer id (optional). The e-mail identifies the customer; an e-mail that is already a customer keeps its customer id.">
            <textarea rows={4} value={text} onChange={e => setText(e.target.value)} placeholder={'asha@gmail.com, Asha Rao, 2\nravi@example.com\nmeena@example.com, Meena, 1, 1042'} />
          </Field>
          <Field label="Reply within (days, optional)">
            <input inputMode="numeric" value={days} onChange={e => setDays(e.target.value)} style={{ maxWidth: 120 }} />
          </Field>
          <p><button className="btn btn-primary" onClick={sendInvites} disabled={busy}>{busy ? 'Sending...' : 'Send invitations'}</button></p>
          {result && result.skipped.length > 0 && (
            <ul className="muted">
              {result.skipped.map((s, k) => <li key={k}><strong>{s.email ?? '(blank)'}</strong>: {s.message}</li>)}
            </ul>
          )}
          {result && result.seatsLeft !== null && result.pendingInvites > result.seatsLeft && (
            <div className="banner banner-warn">{result.pendingInvites} invitations are waiting but only {result.seatsLeft} seat(s) are left; seats go to whoever accepts first.</div>
          )}
          {list.loading && !list.data && <Loading />}
          {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
          {list.data && <DataTable columns={cols} rows={list.data} rowKey={i => i.id} empty="No invitations yet." />}
        </>
      )}

      {revoking && (
        <ConfirmModal title="Withdraw this invitation?" confirmLabel="Withdraw" danger
          rows={[['E-mail', revoking.email], ['Status', label(revoking.status)], ['Acting as', actor]]}
          note={<Field label="Reason (optional)"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={200} /></Field>}
          onConfirm={async () => {
            await send('POST', `${base}/${revoking.id}/revoke`, reason.trim() ? { reason: reason.trim() } : {});
            setRevoking(null);
            setNotice({ tone: 'good', text: `Invitation to ${revoking.email} withdrawn.` });
            refresh();
          }}
          onClose={() => setRevoking(null)} />
      )}

      {enrolling && quote && (
        <EnrolInvitationDialog inv={enrolling} q={quote} actor={actor}
          onSubmit={async body => {
            const r = await send<PortalInvitation>('POST', `${base}/${enrolling.id}/enrol`, body);
            setEnrolling(null);
            setNotice({ tone: r.ticketsEnrolled >= r.tickets ? 'good' : 'warn',
              text: `${r.email}: ${r.ticketsEnrolled} of ${r.tickets} ticket(s) enrolled${r.customerId ? ` for customer ${r.customerId}` : ''}.` });
            refresh();
          }}
          onClose={() => setEnrolling(null)} />
      )}
    </Card>
  );
}

function EnrolInvitationDialog({ inv, q, actor, onSubmit, onClose }: {
  inv: PortalInvitation; q: ChitQuote; actor: string; onSubmit: (body: Record<string, unknown>) => Promise<void>; onClose: () => void;
}) {
  const [payout, setPayout] = useState('');
  const [guarantor, setGuarantor] = useState('');
  const [lien, setLien] = useState('');
  const remaining = inv.tickets - inv.ticketsEnrolled;

  async function confirm() {
    if (q.guarantorRequired && !wholeNumber(guarantor)) throw new Error('A guarantor customer id is required');
    if (q.lienAmount > 0 && !lien.trim()) throw new Error('The lien reference is required');
    const body: Record<string, unknown> = { feesReceived: true };
    if (payout.trim()) body.payoutAccountRef = payout.trim();
    if (q.guarantorRequired) body.guarantorCustomerId = Number(guarantor.trim());
    if (q.lienAmount > 0) body.lienReference = lien.trim();
    await onSubmit(body);
  }

  return (
    <ConfirmModal title={`Enrol ${inv.email}`} confirmLabel={`Enrol ${remaining} ticket(s), ${money(q.cashDue * remaining)} received`}
      rows={[['Customer id', inv.customerId ?? '-'], ['Tickets to enrol', remaining], ['Cash per ticket', money(q.cashDue)], ['Total to have received', money(q.cashDue * remaining)], ['Acting as', actor]]}
      note={(
        <>
          <Field label="Payout account (optional)"><input value={payout} onChange={e => setPayout(e.target.value)} maxLength={60} /></Field>
          {q.guarantorRequired && <Field label="Guarantor customer id"><input inputMode="numeric" value={guarantor} onChange={e => setGuarantor(e.target.value)} /></Field>}
          {q.lienAmount > 0 && <Field label="Lien reference"><input value={lien} onChange={e => setLien(e.target.value)} /></Field>}
          <p className="muted">Confirming records that the amounts in the quote were received for each ticket. Tickets already enrolled are not made twice.</p>
        </>)}
      onConfirm={confirm} onClose={onClose} />
  );
}
