import { ReactNode } from 'react';
import { get } from '../api';
import { hasRole } from '../access';
import { useAuth } from '../auth';
import { label, Tone } from '../format';
import { useLoad } from '../hooks';
import { Badge, ErrorBox, Loading } from './ui';

export function groupTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': return 'good';
    case 'DRAFT': case 'OPEN_FOR_ENROLMENT': return 'info';
    case 'FULL': case 'CLOSING': return 'warn';
    case 'CANCELLED': return 'bad';
    default: return 'neutral'; // CLOSED
  }
}

/** One tone mapping for every status word the chit screens show (groups, tickets, fees, security, cycles). */
export function chitTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': case 'RECEIVED': case 'VERIFIED': case 'HELD': case 'COMPLETED': case 'SETTLED': case 'DISBURSED': case 'PAID': case 'APPLIED': return 'good';
    case 'DRAFT': case 'OPEN_FOR_ENROLMENT': case 'ENROLLED': case 'PENDING': case 'SCHEDULED': case 'OPEN': case 'COLLECTION_OPEN': case 'AUCTION_OPEN':
    case 'DECLARED': case 'APPROVED': return 'info';
    case 'FULL': case 'CLOSING': case 'PRIZED': case 'REFUNDED': case 'RELEASED': case 'PAYOUT_PENDING': case 'PENDING_APPROVAL': case 'PART_PAID':
    case 'ROLLED_OVER': case 'AUCTION_CLOSED': return 'warn';
    case 'CANCELLED': case 'DEFAULTED': case 'FAILED': case 'REJECTED': case 'WITHDRAWN': return 'bad';
    default: return 'neutral'; // CLOSED, EXITED, TRANSFERRED
  }
}

export function GroupBadge({ status }: { status: string }) {
  return <Badge tone={groupTone(status)}>{label(status)}</Badge>;
}

export function ChitBadge({ status }: { status: string }) {
  return <Badge tone={chitTone(status)}>{label(status)}</Badge>;
}

/** Whether this login may change chit data (the server enforces it; this only hides buttons that would be refused). */
export function useCanChangeChit(): boolean {
  const { me } = useAuth();
  return hasRole(me, 'CHIT_OPS');
}

/** The three "run now" buttons (demand, late fee, default check) may be pressed by the chit and the batch operators. */
export function useCanRunChitJobs(): boolean {
  const { me } = useAuth();
  return hasRole(me, 'CHIT_OPS', 'BATCH_OPS');
}

export function ChitGate({ children }: { children: ReactNode }) {
  const st = useLoad(() => get<{ enabled: boolean }>('/chit/status'), []);
  if (st.loading && !st.data) return <Loading what="Checking chit service" />;
  if (st.error) return <ErrorBox message={st.error} onRetry={st.reload} />;
  if (st.data && !st.data.enabled) {
    return (
      <div className="banner banner-warn" role="alert">
        The chit screens are not set up. Set CHIT_SERVICE_URL (the public address of chitfund-service) and CHIT_SERVICE_PASSWORD
        (the password chitfund-service expects for CHIT_SERVICE_USERNAME) on chitfund-admin-service and restart it.
      </div>
    );
  }
  return <>{children}</>;
}

/** Whole-number text field check used by the chit forms. */
export const wholeNumber = (v: string) => /^\d+$/.test(v.trim());
