import { useState } from 'react';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { dateTime, date, label, Tone } from '../format';
import { useLoad } from '../hooks';
import type { BatchExecution, BatchJob } from '../types';

function runTone(status: string | null): Tone {
  switch (status) {
    case 'COMPLETED': return 'good';
    case 'FAILED': case 'ABANDONED': case 'STOPPED': return 'bad';
    case null: return 'neutral';
    default: return 'warn';
  }
}

export default function Batch() {
  const { data, error, loading, reload } = useLoad(() => get<BatchJob[]>('/batch/jobs'), []);
  const [selected, setSelected] = useState<string | null>(null);

  const jobCols: Col<BatchJob>[] = [
    { key: 'p', header: 'Order', align: 'right', render: r => r.priority ?? '-' },
    { key: 'n', header: 'Job', render: r => <strong className="mono">{r.jobName}</strong> },
    { key: 'c', header: 'Category', render: r => label(r.jobCategory) },
    { key: 'cr', header: 'Schedule (cron)', render: r => <span className="mono">{r.cronExpression ?? '-'}</span> },
    { key: 'e', header: 'Enabled', render: r => <Badge tone={r.enabled ? 'good' : 'neutral'}>{r.enabled ? 'Enabled' : 'Disabled'}</Badge> },
    { key: 'l', header: 'Last run', render: r => (r.running ? <Badge tone="info">Running</Badge>
      : r.lastStatus ? <Badge tone={runTone(r.lastStatus)}>{label(r.lastStatus)}</Badge> : <span className="muted">Never</span>) },
    { key: 'ls', header: 'Started', render: r => dateTime(r.lastRunStartTime) },
    { key: 'nx', header: 'Next run', render: r => dateTime(r.nextFireTime) },
    { key: 'a', header: '', render: r => <button className="btn" onClick={() => setSelected(selected === r.jobName ? null : r.jobName)}>
      {selected === r.jobName ? 'Hide runs' : 'Recent runs'}</button> },
  ];

  return (
    <>
      <PageHeader title="Batch jobs" sub="The chit jobs that chitfund-batch-service runs, with the last outcome and the next scheduled run"
        actions={<button className="btn" onClick={reload}>Refresh</button>} />
      {loading && !data && <Loading what="Contacting the batch service" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <Card title={`Configured jobs (${data.length})`}>
            <DataTable columns={jobCols} rows={data} rowKey={r => r.jobName} empty="No jobs are configured" />
            <p className="muted" style={{ marginBottom: 0 }}>
              Job settings (schedule, enabled, order) are changed on chitfund-batch-service itself (its batch-admin API), not on this screen.
              The daily jobs also run in order when you start End of day.
            </p>
          </Card>
          {selected && <Executions jobName={selected} />}
        </>
      )}
    </>
  );
}

function Executions({ jobName }: { jobName: string }) {
  const { data, error, loading, reload } = useLoad(() => get<BatchExecution[]>(`/batch/jobs/${encodeURIComponent(jobName)}/executions`, { limit: 10 }), [jobName]);
  const cols: Col<BatchExecution>[] = [
    { key: 'id', header: 'Execution', render: r => r.jobExecutionId },
    { key: 'bd', header: 'Business date', render: r => date(r.businessDate) },
    { key: 's', header: 'Status', render: r => <Badge tone={runTone(r.status)}>{label(r.status)}</Badge> },
    { key: 'x', header: 'Exit code', render: r => r.exitCode ?? '-' },
    { key: 'st', header: 'Started', render: r => dateTime(r.startTime) },
    { key: 'en', header: 'Ended', render: r => dateTime(r.endTime) },
    { key: 'sk', header: 'Skipped', align: 'right', render: r => r.skipCount },
  ];
  return (
    <Card title={<>Recent runs of <span className="mono">{jobName}</span></>}>
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && <DataTable columns={cols} rows={data} rowKey={r => r.jobExecutionId} empty="This job has not run yet" />}
    </Card>
  );
}
