import { FormEvent, ReactNode, useRef, useState } from 'react';
import { date } from '../format';
import { KeyValues } from './ui';

/** A fresh idempotency key; crypto.randomUUID needs a secure context, so fall back for plain http. */
export function newKey(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * One key per distinct request: the same form values keep the same key, so a double click or a retry after a lost
 * answer is applied once by the chit service; changing a value, or finishing the request, starts a new one.
 */
export function useIdempotencyKey() {
  const ref = useRef<{ fp: string; key: string } | null>(null);
  return {
    keyFor(fingerprint: string): string {
      if (!ref.current || ref.current.fp !== fingerprint) ref.current = { fp: fingerprint, key: newKey() };
      return ref.current.key;
    },
    done() { ref.current = null; },
  };
}

/** Last day of the month is kept when the target month is shorter (31 Jan + 1 month = 28/29 Feb). */
export function addMonths(iso: string, months: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const total = (m - 1) + months;
  const ny = y + Math.floor(total / 12);
  const nm = ((total % 12) + 12) % 12;
  const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
  const day = Math.min(d, last);
  return `${ny}-${String(nm + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

/**
 * The confirmation step: shows the business date and what is about to happen. `onConfirm` does the call and closes the
 * dialog itself on success; anything it throws is shown here and the dialog stays open so the user can retry or cancel.
 * `typeToConfirm` makes the user type a word first, for actions that cannot be undone.
 */
export function ConfirmModal({ title, businessDate, rows, note, confirmLabel, danger, typeToConfirm, onConfirm, onClose }: {
  title: string; businessDate?: string; rows: [string, ReactNode][]; note?: ReactNode; confirmLabel: string;
  danger?: boolean; typeToConfirm?: string; onConfirm: () => Promise<void>; onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [typed, setTyped] = useState('');
  const ready = !typeToConfirm || typed === typeToConfirm;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || busy) return;
    setBusy(true);
    setErr(null);
    try {
      await onConfirm();
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label={title} onSubmit={submit}>
        <h2>{title}</h2>
        {businessDate && <p style={{ fontSize: 16 }}>Business date: <strong>{date(businessDate)}</strong></p>}
        <KeyValues items={rows} />
        {note && (typeof note === 'string' ? <p style={{ marginBottom: 0 }}>{note}</p> : <div className="modal-note">{note}</div>)}
        {typeToConfirm && (
          <label className="field wide" style={{ marginTop: 12 }}>
            <span>Type {typeToConfirm} to confirm</span>
            <input value={typed} onChange={e => setTyped(e.target.value)} autoFocus aria-label={`Type ${typeToConfirm} to confirm`} />
          </label>
        )}
        {err && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className={danger ? 'btn btn-danger' : 'btn btn-primary'} disabled={busy || !ready}>
            {busy ? 'Working...' : confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
}

export function Field({ label, hint, wide, children }: { label: string; hint?: ReactNode; wide?: boolean; children: ReactNode }) {
  return (
    <label className={wide ? 'field wide' : 'field'}>
      <span>{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}
