import { FormEvent, useState } from 'react';
import { get, send } from '../api';
import { hasRole } from '../access';
import { useAuth } from '../auth';
import { ChitGate, useCanChangeChit } from '../components/chit';
import { ActingAsBar, NoticeBanner } from '../components/actingAs';
import DataTable, { Col } from '../components/DataTable';
import { ConfirmModal, Field, Notice } from '../components/ops';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, label } from '../format';
import { useLoad } from '../hooks';
import type { BusinessDateView, CalendarView, Holiday, WorkingDayView } from '../types';

export default function Calendar() {
  return (
    <>
      <PageHeader title="Business date and holidays" sub="The date every chit rule uses, and the working-day calendar. Both belong to the chit service." />
      <ChitGate><Body /></ChitGate>
    </>
  );
}

function Body() {
  const { me } = useAuth();
  const canEdit = useCanChangeChit();
  const isAdmin = hasRole(me);   // ADMIN only: hasRole with no roles is true only for ADMIN
  const bd = useLoad(() => get<BusinessDateView>('/business-date'), []);
  const [year, setYear] = useState<number | null>(null);
  const shownYear = year ?? (bd.data ? Number(bd.data.businessDate.slice(0, 4)) : null);
  const cal = useLoad(
    () => (shownYear === null ? Promise.resolve(null) : get<CalendarView>('/calendar/holidays', { year: shownYear })),
    [shownYear],
  );
  const [notice, setNotice] = useState<Notice>(null);
  const [setting, setSetting] = useState(false);
  const [removing, setRemoving] = useState<Holiday | null>(null);

  const b = bd.data;
  const cols: Col<Holiday>[] = [
    { key: 'd', header: 'Date', render: h => <strong>{date(h.date)}</strong> },
    { key: 'w', header: 'Day', render: h => new Date(`${h.date}T00:00:00`).toLocaleDateString('en-GB', { weekday: 'long' }) },
    { key: 'n', header: 'Holiday', render: h => h.name },
    { key: 'by', header: 'Added by', render: h => h.createdBy ?? '-' },
    { key: 'a', header: '', render: h => (canEdit ? <button className="btn" onClick={() => setRemoving(h)}>Remove</button> : null) },
  ];

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={notice} />
      {bd.loading && !b && <Loading />}
      {bd.error && <ErrorBox message={bd.error} onRetry={bd.reload} />}
      {b && (
        <>
          <div className="kpi-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 12, marginBottom: 14 }}>
            <Kpi title="Business date" value={date(b.businessDate)} sub={b.workingDay ? 'A working day' : 'Not a working day'} />
            <Kpi title="Status" value={b.status === 'EOD_RUNNING' ? 'End of day running' : 'Open'} tone={b.status === 'EOD_RUNNING' ? 'warn' : 'good'}
              sub={b.mode === 'SYSTEM' ? 'Follows the wall clock' : 'Moves only when end of day closes it'} />
            <Kpi title="Last closed date" value={date(b.previousBusinessDate)} />
            <Kpi title="Next business date" value={date(b.nextBusinessDate)} sub={`Calendar date ${date(b.systemDate)}`} />
          </div>
          {b.mode === 'SYSTEM' && (
            <div className="banner banner-warn">
              The chit service runs on the wall-clock date (CHIT_BUSINESS_DATE_MODE=SYSTEM), so the business date cannot be moved here or by end of day.
            </div>
          )}
          {isAdmin && b.mode !== 'SYSTEM' && (
            <Card title="Set the business date by hand"
              actions={<button className="btn" disabled={b.status === 'EOD_RUNNING'} onClick={() => setSetting(true)}>Set date...</button>}>
              <p className="muted" style={{ margin: 0 }}>
                For test environments only: the chit service allows it only when CHIT_RESET_ENABLED=true, and only forward (a date in the past needs Clear
                all data under Maintenance). Normally the date moves by itself when End of day finishes.
              </p>
            </Card>
          )}
        </>
      )}

      <WorkingDayCheck />

      <Card title="Holidays and weekends"
        actions={
          <span className="filters" style={{ margin: 0 }}>
            <button className="btn" disabled={shownYear === null} onClick={() => setYear((shownYear ?? 0) - 1)}>Previous year</button>
            <strong>{shownYear ?? ''}</strong>
            <button className="btn" disabled={shownYear === null} onClick={() => setYear((shownYear ?? 0) + 1)}>Next year</button>
          </span>
        }>
        {cal.loading && !cal.data && <Loading />}
        {cal.error && <ErrorBox message={cal.error} onRetry={cal.reload} />}
        {cal.data && (
          <>
            <p style={{ marginTop: 0 }}>
              Weekly off days: {cal.data.weekend.length === 0 ? <span className="muted">none</span>
                : cal.data.weekend.map(d => <Badge key={d} tone="neutral">{label(d)}</Badge>)}
              {' '}<span className="muted">(set on the chit service with CHIT_WEEKEND_DAYS)</span>
            </p>
            <DataTable columns={cols} rows={cal.data.holidays} rowKey={h => h.date} empty={`No holidays listed for ${shownYear}`} />
            {canEdit && <AddHoliday defaultYear={shownYear} onDone={text => { setNotice({ tone: 'good', text }); cal.reload(); }} />}
            <p className="muted" style={{ marginBottom: 0 }}>
              A holiday applies to groups activated after it is added: cycle dates already laid out at activation do not change.
            </p>
          </>
        )}
      </Card>

      {setting && b && (
        <SetDateDialog current={b.businessDate} onClose={() => setSetting(false)}
          onDone={text => { setSetting(false); setNotice({ tone: 'good', text }); bd.reload(); cal.reload(); }} />
      )}
      {removing && (
        <ConfirmModal
          title="Remove this holiday?"
          rows={[['Date', date(removing.date)], ['Holiday', removing.name]]}
          note="Groups activated from now on will treat this date as a working day. Cycle dates already laid out do not change."
          confirmLabel="Remove holiday"
          danger
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            await send('DELETE', `/calendar/holidays/${removing.date}`);
            setRemoving(null);
            setNotice({ tone: 'good', text: `${date(removing.date)} (${removing.name}) is no longer a holiday.` });
            cal.reload();
            bd.reload();
          }}
        />
      )}
    </>
  );
}

function WorkingDayCheck() {
  const [day, setDay] = useState('');
  const [result, setResult] = useState<WorkingDayView | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function check(e: FormEvent) {
    e.preventDefault();
    if (!day) return;
    setBusy(true);
    setErr(null);
    try {
      setResult(await get<WorkingDayView>('/calendar/working-day', { date: day }));
    } catch (ex) {
      setResult(null);
      setErr(ex instanceof Error ? ex.message : String(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Is this a working day?">
      <form className="filters" onSubmit={check}>
        <input type="date" value={day} onChange={e => { setDay(e.target.value); setResult(null); }} aria-label="Date to check" />
        <button className="btn" disabled={!day || busy}>Check</button>
        {result && (
          <span role="status">
            {date(result.date)}: <Badge tone={result.workingDay ? 'good' : 'warn'}>{result.workingDay ? 'Working day' : 'Not a working day'}</Badge>
          </span>
        )}
      </form>
      {err && <div className="banner banner-bad" role="alert">{err}</div>}
    </Card>
  );
}

function AddHoliday({ defaultYear, onDone }: { defaultYear: number | null; onDone: (text: string) => void }) {
  const [day, setDay] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!day || !name.trim()) return;
    setBusy(true);
    setErr(null);
    try {
      await send('PUT', `/calendar/holidays/${day}`, { name: name.trim() });
      onDone(`${date(day)} is now a holiday: ${name.trim()}.`);
      setDay('');
      setName('');
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="filters" onSubmit={submit} style={{ marginTop: 12 }}>
      <input type="date" value={day} onChange={e => setDay(e.target.value)} aria-label="Holiday date"
        min={defaultYear ? `${defaultYear}-01-01` : undefined} max={defaultYear ? `${defaultYear}-12-31` : undefined} />
      <input value={name} maxLength={80} onChange={e => setName(e.target.value)} placeholder="Holiday name, like Diwali" aria-label="Holiday name" />
      <button className="btn btn-primary" disabled={busy || !day || !name.trim()}>{busy ? 'Saving...' : 'Add or rename holiday'}</button>
      {err && <div className="banner banner-bad" role="alert" style={{ flexBasis: '100%' }}>{err}</div>}
    </form>
  );
}

function SetDateDialog({ current, onClose, onDone }: { current: string; onClose: () => void; onDone: (text: string) => void }) {
  const [day, setDay] = useState('');
  return (
    <ConfirmModal
      title="Set the business date"
      businessDate={current}
      rows={[['Current business date', date(current)], ['New business date', day ? date(day) : '-']]}
      note={(
        <>
          <Field label="New business date" hint="Must be after the current date. This cannot be undone except by Clear all data.">
            <input type="date" value={day} min={current} onChange={e => setDay(e.target.value)} />
          </Field>
        </>
      )}
      confirmLabel="Set business date"
      danger
      onClose={onClose}
      onConfirm={async () => {
        if (!day) throw new Error('Pick the new business date');
        const r = await send<BusinessDateView>('PUT', '/calendar/business-date', { businessDate: day });
        onDone(`The business date is now ${date(r.businessDate)}.`);
      }}
    />
  );
}
