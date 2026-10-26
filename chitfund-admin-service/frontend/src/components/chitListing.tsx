import { useState } from 'react';
import { send } from '../api';
import { Field, Notice } from './ops';
import { Card } from './ui';
import { NoticeBanner } from './actingAs';

export const PLAN_CATEGORIES: [string, string][] = [
  ['', 'None (shows only under All Plans)'], ['EDUCATION', 'Education'], ['HOME', 'Home'], ['WEDDING', 'Wedding'],
  ['SHORT_TERM', 'Short term'], ['RETIREMENT', 'Retirement'], ['BUSINESS', 'Business'],
];

/** How a group is listed on the customer portal. Saving sets all three values; blank ones are cleared. */
export function ListingCard({ code }: { code: string }) {
  const [category, setCategory] = useState('');
  const [branch, setBranch] = useState('');
  const [state, setState] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);

  async function save() {
    setBusy(true);
    setNotice(null);
    try {
      await send('PUT', `/chit/groups/${encodeURIComponent(code)}/listing`, { planCategory: category, branch: branch.trim(), state: state.trim() });
      setNotice({ tone: 'good', text: 'Saved. The plan list on the portal updates within a minute.' });
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Listing on the customer portal">
      <NoticeBanner notice={notice} />
      <p className="muted">Saving replaces all three values below (blank clears them). The current values are not shown here.</p>
      <Field label="Category">
        <select value={category} onChange={e => setCategory(e.target.value)}>
          {PLAN_CATEGORIES.map(([v, t]) => <option key={v} value={v}>{t}</option>)}
        </select>
      </Field>
      <Field label="Branch"><input maxLength={60} value={branch} onChange={e => setBranch(e.target.value)} /></Field>
      <Field label="State"><input maxLength={40} value={state} onChange={e => setState(e.target.value)} /></Field>
      <p><button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Saving...' : 'Save listing'}</button></p>
    </Card>
  );
}
