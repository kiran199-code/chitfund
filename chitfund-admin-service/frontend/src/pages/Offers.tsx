import { useState } from 'react';
import { get, send } from '../api';
import { Field, Notice } from '../components/ops';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import DataTable, { Col } from '../components/DataTable';
import { date, label, Tone, todayIso } from '../format';
import { useLoad } from '../hooks';
import type { PortalOffer } from '../types';

const TONE: Record<string, Tone> = { LIVE: 'good', SCHEDULED: 'info', ENDED: 'neutral', HIDDEN: 'neutral' };

interface Draft { title: string; body: string; linkUrl: string; imageUrl: string; audience: 'PUBLIC' | 'MEMBERS'; startsOn: string; endsOn: string }
const blank = (): Draft => ({ title: '', body: '', linkUrl: '', imageUrl: '', audience: 'MEMBERS', startsOn: todayIso(), endsOn: '' });

/** Offers and marketing material shown on the customer portal: text and links only, written here by staff. */
export default function Offers() {
  const status = useLoad(() => get<{ enabled: boolean }>('/portal/status'), []);
  const [tick, setTick] = useState(0);
  const list = useLoad<PortalOffer[]>(() => (status.data?.enabled ? get<PortalOffer[]>('/portal/offers') : Promise.resolve([])), [tick, status.data?.enabled]);
  const [draft, setDraft] = useState<Draft>(blank());
  const [editing, setEditing] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);

  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setDraft(d => ({ ...d, [k]: v }));

  function edit(o: PortalOffer) {
    setEditing(o.id);
    setDraft({ title: o.title, body: o.body, linkUrl: o.linkUrl ?? '', imageUrl: o.imageUrl ?? '', audience: o.audience, startsOn: o.startsOn, endsOn: o.endsOn ?? '' });
    setNotice(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function reset() { setEditing(null); setDraft(blank()); }

  async function save() {
    if (!draft.title.trim()) { setNotice({ tone: 'bad', text: 'A title is required.' }); return; }
    if (!draft.startsOn) { setNotice({ tone: 'bad', text: 'Choose the first day the offer shows.' }); return; }
    if (draft.endsOn && draft.endsOn < draft.startsOn) { setNotice({ tone: 'bad', text: 'The last day cannot be before the first day.' }); return; }
    const body = {
      title: draft.title.trim(), body: draft.body.trim(), linkUrl: draft.linkUrl.trim() || null, imageUrl: draft.imageUrl.trim() || null,
      audience: draft.audience, startsOn: draft.startsOn, endsOn: draft.endsOn || null,
    };
    setBusy(true);
    setNotice(null);
    try {
      await send<PortalOffer>('POST', editing ? `/portal/offers/${editing}` : '/portal/offers', body);
      setNotice({ tone: 'good', text: editing ? 'Offer updated.' : 'Offer saved.' });
      reset();
      setTick(t => t + 1);
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  async function toggle(o: PortalOffer) {
    setNotice(null);
    try {
      await send('POST', `/portal/offers/${o.id}/${o.active ? 'hide' : 'show'}`);
      setTick(t => t + 1);
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    }
  }

  const cols: Col<PortalOffer>[] = [
    { key: 'title', header: 'Offer', render: o => <>{o.title}{o.body ? <div className="muted">{o.body.length > 90 ? `${o.body.slice(0, 90)}...` : o.body}</div> : null}</> },
    { key: 'aud', header: 'Shown to', render: o => (o.audience === 'PUBLIC' ? 'Everyone (also before sign-in)' : 'Signed-in members') },
    { key: 'when', header: 'Dates', render: o => `${date(o.startsOn)} to ${o.endsOn ? date(o.endsOn) : 'no end'}` },
    { key: 'status', header: 'Status', render: o => <Badge tone={TONE[o.status] ?? 'neutral'}>{label(o.status)}</Badge> },
    {
      key: 'act', header: '', render: o => (
        <span style={{ whiteSpace: 'nowrap' }}>
          <button className="btn" onClick={() => edit(o)}>Edit</button>{' '}
          <button className="btn" onClick={() => toggle(o)}>{o.active ? 'Hide' : 'Show'}</button>
        </span>),
    },
  ];

  return (
    <>
      <PageHeader title="Portal offers" sub="Offers and marketing material that members see on the customer portal" />
      {status.loading && !status.data && <Loading what="Checking the customer portal" />}
      {status.error && <ErrorBox message={status.error} onRetry={status.reload} />}
      {status.data && !status.data.enabled && (
        <div className="banner banner-warn" role="alert">
          The customer portal is not set up. Set PORTAL_SERVICE_URL and PORTAL_ADMIN_PASSWORD on chitfund-admin-service and restart it.
        </div>
      )}
      {status.data?.enabled && (
        <>
          <Card title={editing ? `Edit offer #${editing}` : 'New offer'}>
            {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
            <Field label="Title"><input value={draft.title} maxLength={120} onChange={e => set('title', e.target.value)} /></Field>
            <Field wide label="Text" hint="Up to 600 characters.">
              <textarea rows={3} value={draft.body} maxLength={600} onChange={e => set('body', e.target.value)} />
            </Field>
            <Field label="Link (optional)" hint="A web address starting with https://"><input value={draft.linkUrl} onChange={e => set('linkUrl', e.target.value)} /></Field>
            <Field label="Picture link (optional)" hint="Address of a picture already on the web; nothing is uploaded here."><input value={draft.imageUrl} onChange={e => set('imageUrl', e.target.value)} /></Field>
            <Field label="Shown to">
              <select value={draft.audience} onChange={e => set('audience', e.target.value as Draft['audience'])}>
                <option value="MEMBERS">Signed-in members only</option>
                <option value="PUBLIC">Everyone, including the sign-in page</option>
              </select>
            </Field>
            <Field label="First day"><input type="date" value={draft.startsOn} onChange={e => set('startsOn', e.target.value)} /></Field>
            <Field label="Last day (optional)"><input type="date" value={draft.endsOn} onChange={e => set('endsOn', e.target.value)} /></Field>
            <p>
              <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Saving...' : editing ? 'Save changes' : 'Save offer'}</button>{' '}
              {editing ? <button className="btn" onClick={reset} disabled={busy}>Cancel</button> : null}
            </p>
          </Card>
          <Card title="All offers" actions={<button className="btn" onClick={() => setTick(t => t + 1)}>Refresh</button>}>
            {list.loading && !list.data && <Loading />}
            {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
            {list.data && <DataTable columns={cols} rows={list.data} rowKey={o => o.id} empty="No offers yet." />}
          </Card>
        </>
      )}
    </>
  );
}
