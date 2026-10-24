import { FormEvent, useEffect, useRef, useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { count, date } from '../format';
import { useLoad } from '../hooks';
import type { ClearDataResult, MaintenanceStatus } from '../types';

const TABLE_NAMES: Record<string, string> = {
  chit_group: 'Chit groups',
  chit_group_rules_version: 'Group rule versions',
  chit_ticket: 'Tickets',
  chit_subscriber_link: 'Subscriber links',
  chit_cycle: 'Cycles',
  chit_fee_receipt: 'Fee receipts (enrolment, registration)',
  chit_demand: 'Demands (instalments raised)',
  chit_payment: 'Payments',
  chit_payment_allocation: 'Payment allocations',
  chit_late_fee: 'Late fees',
  chit_auction: 'Auctions',
  chit_bid: 'Bids',
  chit_draw_record: 'Draw records',
  chit_prize_payout: 'Prize payouts',
  chit_dividend: 'Dividends',
  chit_security: 'Security deposits and guarantees',
  chit_default_case: 'Default cases',
  chit_ticket_transfer: 'Ticket transfers',
  chit_closure_report: 'Closure reports',
  ledger_account: 'Ledger accounts',
  ledger_journal: 'Ledger journals',
  ledger_line: 'Ledger lines',
  idempotency_key: 'Idempotency log',
  chit_event: 'Events',
  audit_log: 'Audit log',
};

const BATCH_TABLE_NAMES: Record<string, string> = {
  batch_job_instance: 'Job instances',
  batch_job_execution: 'Job executions',
  batch_job_execution_params: 'Job parameters',
  batch_job_execution_context: 'Job execution contexts',
  batch_step_execution: 'Step executions',
  batch_step_execution_context: 'Step execution contexts',
  batch_job_audit_log: 'Step audit log',
  batch_job_error_records: 'Skipped and failed items',
};

type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

export default function Maintenance() {
  const { data, error, loading, reload } = useLoad(() => get<MaintenanceStatus>('/maintenance/status'), []);
  const [notice, setNotice] = useState<Notice>(null);
  const [confirming, setConfirming] = useState(false);

  const eodRunning = data?.businessDateStatus === 'EOD_RUNNING';
  const batch = data?.batch;
  const batchBlocks = !!batch?.configured && !(batch.reachable && batch.resetEnabled && !batch.busy);
  // The business date says "end of day running" but the batch service reports nothing running: that run failed, so it must not block a clear.
  const eodStuck = eodRunning && !!batch?.configured && !!batch.reachable && !batch.busy;
  const canClear = !!data?.resetEnabled && (!eodRunning || eodStuck) && !batchBlocks;
  const chitRows = data ? Object.values(data.rowCounts).reduce((a, b) => a + b, 0) : 0;
  const batchRows = batch?.rowCounts ? Object.values(batch.rowCounts).reduce((a, b) => a + b, 0) : 0;
  const totalRows = chitRows + batchRows;

  const countCols: Col<[string, number]>[] = [
    { key: 't', header: 'Chit data', render: r => TABLE_NAMES[r[0]] ?? r[0] },
    { key: 'n', header: 'Rows', align: 'right', render: r => count(r[1]) },
  ];
  const batchCountCols: Col<[string, number]>[] = [
    { key: 't', header: 'Batch job history', render: r => BATCH_TABLE_NAMES[r[0]] ?? r[0] },
    { key: 'n', header: 'Rows', align: 'right', render: r => count(r[1]) },
  ];

  return (
    <>
      <PageHeader title="Maintenance" sub="Start again from scratch in a test environment" />
      <div className="banner banner-warn">
        These actions change data, so they are done by chitfund-service and chitfund-batch-service on this console's behalf; the console
        itself never writes to a database. Use them in sandbox and test environments only.
      </div>
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <section className="card danger-zone">
          <header className="card-head">
            <h3>Clear all data</h3>
            <div className="card-actions">
              <button className="btn btn-danger" disabled={!canClear} onClick={() => setConfirming(true)}>Clear all data...</button>
            </div>
          </header>
          {!data.resetEnabled && (
            <div className="banner banner-warn">
              Clearing is switched off on chitfund-service. Set CHIT_RESET_ENABLED=true on it (Render: Environment tab) and redeploy to allow it.
            </div>
          )}
          {data.resetEnabled && batch?.configured && !batch.reachable && (
            <div className="banner banner-warn">
              chitfund-batch-service could not be reached{batch.error ? `: ${batch.error}` : ''}. Open its address once to wake it, then reload
              this page. Nothing is cleared while it cannot be checked, so the chit data is never cleared while the old job history is left behind.
            </div>
          )}
          {data.resetEnabled && batch?.configured && batch.reachable && !batch.resetEnabled && (
            <div className="banner banner-warn">
              Clearing is switched off on chitfund-batch-service. Set BATCH_RESET_ENABLED=true on it (Render: Environment tab) and redeploy to allow it.
            </div>
          )}
          {batch?.configured && batch.reachable && batch.resetEnabled && batch.busy && (
            <div className="banner banner-warn">{batch.busyReason ?? 'The batch service is busy'}. Wait for it to finish before clearing.</div>
          )}
          {batch && !batch.configured && (
            <div className="banner banner-warn">
              This console is not connected to chitfund-batch-service (BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD), so its job history will not be
              cleared with the chit data, and end of day can then refuse dates that already ran.
            </div>
          )}
          {eodRunning && !eodStuck && <div className="banner banner-warn">End of day is running. Wait for it to finish before clearing.</div>}
          {eodStuck && (
            <div className="banner banner-warn">
              The business date shows end of day running, but the batch service has nothing running, so that run failed or stopped. Clear all
              data is allowed and will reopen the date as today's date. (You can also fix the failed job and run End of day again.)
            </div>
          )}
          <p className="muted">
            Removes every chit group with its tickets, cycles, demands, payments, late fees, security, default cases, the chit ledger,
            the idempotency log and the audit log, and, in chitfund-batch-service, the job execution history. Then it sets the business date
            to today's date (it is {date(data.businessDate)} now) and opens it. It cannot be undone. It keeps the holiday list and the batch
            service's job settings and schedules.
          </p>
          <DataTable
            columns={countCols}
            rows={Object.entries(data.rowCounts)}
            rowKey={r => r[0]}
            footer={<tr><td className="muted">Total</td><td className="num muted" style={{ textAlign: 'right' }}>{count(chitRows)}</td></tr>}
          />
          {batch?.configured && batch.reachable && batch.rowCounts && (
            <DataTable
              columns={batchCountCols}
              rows={Object.entries(batch.rowCounts)}
              rowKey={r => r[0]}
              footer={<tr><td className="muted">Total</td><td className="num muted" style={{ textAlign: 'right' }}>{count(batchRows)}</td></tr>}
            />
          )}
        </section>
      )}
      {confirming && (
        <ClearDialog
          rows={totalRows}
          onClose={() => setConfirming(false)}
          onDone={text => { setConfirming(false); setNotice({ tone: 'good', text }); reload(); }}
        />
      )}
    </>
  );
}

function ClearDialog({ rows, onClose, onDone }: { rows: number; onClose: () => void; onDone: (text: string) => void }) {
  const [word, setWord] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    input.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (word !== 'RESET') return;
    setBusy(true);
    setErr(null);
    try {
      const r = await send<ClearDataResult>('POST', '/maintenance/clear-data', { confirm: 'RESET' });
      onDone(`${r.message} ${count(r.totalRows + (r.batch?.totalRows ?? 0))} rows removed.`);
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label="Clear all data" onSubmit={submit}>
        <h2>Clear all data?</h2>
        <p>
          This permanently deletes {count(rows)} rows: all chit groups, tickets, demands, payments and the chit ledger, and the batch job
          history. The business date is then set to today's date. There is no undo.
        </p>
        <label className="field">
          <span>Type RESET to confirm</span>
          <input ref={input} value={word} onChange={e => setWord(e.target.value)} autoComplete="off" spellCheck={false} />
        </label>
        {err && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-danger" disabled={word !== 'RESET' || busy}>{busy ? 'Clearing...' : 'Clear all data'}</button>
        </div>
      </form>
    </div>
  );
}
