import { Link } from 'react-router-dom';
import { get } from '../api';
import { canOpen } from '../access';
import { useAuth } from '../auth';
import { ChitGate, GroupBadge } from '../components/chit';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, money } from '../format';
import { useLoad } from '../hooks';
import type { BusinessDateView, ChitGroup, ChitGroupPage } from '../types';

export default function Home() {
  return (
    <>
      <PageHeader title="Chitfund Admin" sub="Chit groups, tickets, dues and payments, the business date, and end of day" />
      <ChitGate><Body /></ChitGate>
    </>
  );
}

function Body() {
  const { me } = useAuth();
  const bd = useLoad(() => get<BusinessDateView>('/business-date'), []);
  const active = useLoad(() => get<ChitGroupPage>('/chit/groups', { status: 'ACTIVE', limit: 5 }), []);
  const open = useLoad(() => get<ChitGroupPage>('/chit/groups', { status: 'OPEN_FOR_ENROLMENT', limit: 5 }), []);
  const b = bd.data;

  const cols: Col<ChitGroup>[] = [
    { key: 'c', header: 'Group', render: g => <Link to={`/chit/groups/${encodeURIComponent(g.groupCode)}`}><strong className="mono">{g.groupCode}</strong></Link> },
    { key: 'n', header: 'Name', render: g => g.name },
    { key: 's', header: 'Status', render: g => <GroupBadge status={g.status} /> },
    { key: 'v', header: 'Chit value', align: 'right', render: g => money(g.chitValue) },
    { key: 't', header: 'Tickets', align: 'right', render: g => `${g.ticketsEnrolled} of ${g.ticketCount}` },
  ];

  return (
    <>
      {bd.error && <ErrorBox message={bd.error} onRetry={bd.reload} />}
      {bd.loading && !b && <Loading />}
      {b && (
        <div className="kpi-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 12, marginBottom: 14 }}>
          <Kpi title="Business date" value={date(b.businessDate)} sub={b.workingDay ? 'A working day' : 'Not a working day'} />
          <Kpi title="Status" value={b.status === 'EOD_RUNNING' ? 'End of day running' : 'Open'} tone={b.status === 'EOD_RUNNING' ? 'warn' : 'good'} />
          <Kpi title="Active groups" value={active.data ? active.data.total : '-'} sub="Collecting subscriptions" />
          <Kpi title="Open for enrolment" value={open.data ? open.data.total : '-'} sub="Taking tickets" />
        </div>
      )}
      <Card title="Active groups" actions={<Link className="btn" to="/chit/groups">All groups</Link>}>
        {active.error && <ErrorBox message={active.error} onRetry={active.reload} />}
        {active.data && <DataTable columns={cols} rows={active.data.items} rowKey={g => g.groupCode} empty="No group is active yet" />}
      </Card>
      <Card title="Where to go">
        <ul style={{ margin: 0, paddingLeft: 20, lineHeight: 1.8 }}>
          <li><Link to="/chit/dues">Dues &amp; payments</Link>: overdue and upcoming instalments, record a payment, run the daily jobs now</li>
          {canOpen(me, '/chit/groups/new') && <li><Link to="/chit/groups/new">New group</Link>: set up, enrol, activate</li>}
          <li><Link to="/calendar">Business date &amp; holidays</Link>: the date the chit rules use and the working-day calendar</li>
          {canOpen(me, '/eod') && <li><Link to="/eod">End of day</Link>: close the business date after the daily jobs run</li>}
        </ul>
      </Card>
    </>
  );
}
