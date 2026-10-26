import { useState } from 'react';
import { get, send } from '../api';
import { Notice } from '../components/ops';
import { NoticeBanner } from '../components/actingAs';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import DataTable, { Col } from '../components/DataTable';
import { dateTime, label, Tone } from '../format';
import { useLoad } from '../hooks';
import type { PortalJoinRequest } from '../types';

const TONE: Record<string, Tone> = { PENDING: 'warn', APPROVED: 'good', DECLINED: 'neutral', WITHDRAWN: 'neutral' };
const FILTERS = ['PENDING', 'APPROVED', 'DECLINED', 'WITHDRAWN', ''];

/** Seat requests made on the customer portal. Approving records the decision only; enrol the member afterwards on the group's page. */
export default function JoinRequests() {
  const status = useLoad(() => get<{ enabled: boolean }>('/portal/status'), []);
  const [filter, setFilter] = useState('PENDING');
  const [tick, setTick] = useState(0);
  const [notes, setNotes] = useState<Record<number, string>>({});
  const [busyId, setBusyId] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const list = useLoad<PortalJoinRequest[]>(
    () => (status.data?.enabled ? get<PortalJoinRequest[]>('/portal/join-requests', { status: filter }) : Promise.resolve([])),
    [tick, filter, status.data?.enabled]);

  async function decide(r: PortalJoinRequest, action: 'approve' | 'decline') {
    setBusyId(r.id);
    setNotice(null);
    try {
      await send('POST', `/portal/join-requests/${r.id}/${action}`, { note: (notes[r.id] ?? '').trim() });
      setNotice({ tone: 'good', text: action === 'approve'
        ? `Approved for ${r.email}. Now enrol them on group ${r.groupCode}.` : `Declined the request from ${r.email}.` });
      setTick(t => t + 1);
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusyId(null);
    }
  }

  const cols: Col<PortalJoinRequest>[] = [
    { key: 'who', header: 'Member', render: r => <>{r.memberName || r.email}{r.memberName ? <div className="muted">{r.email}</div> : null}</> },
    { key: 'plan', header: 'Plan', render: r => <>{r.groupName || r.groupCode}<div className="muted">{r.groupCode}</div></> },
    { key: 'tickets', header: 'Seats', align: 'right', render: r => r.tickets },
    { key: 'at', header: 'Requested', render: r => dateTime(r.requestedAt) },
    { key: 'status', header: 'Status', render: r => <Badge tone={TONE[r.status] ?? 'neutral'}>{label(r.status)}</Badge> },
    {
      key: 'act', header: 'Decision', render: r => r.status === 'PENDING' ? (
        <span style={{ whiteSpace: 'nowrap' }}>
          <input aria-label="Note" placeholder="Note (optional)" maxLength={300} value={notes[r.id] ?? ''}
            onChange={e => setNotes(n => ({ ...n, [r.id]: e.target.value }))} />{' '}
          <button className="btn btn-primary" disabled={busyId === r.id} onClick={() => decide(r, 'approve')}>Approve</button>{' '}
          <button className="btn" disabled={busyId === r.id} onClick={() => decide(r, 'decline')}>Decline</button>
        </span>
      ) : <>{r.decidedBy ? `${r.decidedBy}, ${r.decidedAt ? dateTime(r.decidedAt) : ''}` : '-'}{r.note ? <div className="muted">{r.note}</div> : null}</>,
    },
  ];

  return (
    <>
      <PageHeader title="Seat requests" sub="Members ask for a seat on the customer portal. Approving takes no seat and charges nothing; enrol the member afterwards." />
      {status.loading && !status.data && <Loading what="Checking the customer portal" />}
      {status.error && <ErrorBox message={status.error} onRetry={status.reload} />}
      {status.data && !status.data.enabled && (
        <div className="banner banner-warn" role="alert">
          The customer portal is not set up. Set PORTAL_SERVICE_URL and PORTAL_ADMIN_PASSWORD on chitfund-admin-service and restart it.
        </div>
      )}
      {status.data?.enabled && (
        <Card title="Requests" actions={
          <span>
            <select aria-label="Show" value={filter} onChange={e => setFilter(e.target.value)}>
              {FILTERS.map(f => <option key={f} value={f}>{f ? label(f) : 'All'}</option>)}
            </select>{' '}
            <button className="btn" onClick={() => setTick(t => t + 1)}>Refresh</button>
          </span>}>
          <NoticeBanner notice={notice} />
          {list.loading && !list.data && <Loading />}
          {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
          {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty="No requests." />}
        </Card>
      )}
    </>
  );
}
