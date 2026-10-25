// Thin fetch wrapper for chitfund-portal-service. The session is an HttpOnly cookie the browser sends by itself; this code never sees it.
// Every POST carries X-Portal-Client: web, which the server requires (together with an allowed Origin) as its cross-site request guard.

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown, extra?: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { ...(extra ?? {}) };
  if (method === 'POST') {
    headers['Content-Type'] = 'application/json';
    headers['X-Portal-Client'] = 'web';
  }
  let res: Response;
  try {
    res = await fetch('/api/v1/portal' + path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Check your connection and try again.');
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  let data: { code?: string; message?: string } | null = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? `Request failed (${res.status})`);
  }
  return data as T;
}

export interface Member {
  id: number;
  email: string;
  displayName: string | null;
}

export interface GroupInfo {
  status: string;
  chitValue: number;
  subscription: number;
  cycles: number;
  tickets: number;
  startDate: string;
  seatsLeft?: number;
}

export interface Invitation {
  groupCode: string;
  groupName: string;
  tickets: number;
  status: string;
  expiresAt: string;
  group?: GroupInfo;
  groupUnavailable: boolean;
  canDecline: boolean;
  canAccept: boolean;
  ticketsEnrolled: number;
  ticketNos?: number[];
  customerId?: number;
  cashDuePerTicket?: number;
}

export interface PortalConfig {
  googleClientId: string;
}

export interface Ticket {
  groupCode: string; groupName: string; groupStatus: string; ticketNo: number; ticketStatus: string; chitValue: number; subscription: number;
  frequency: string; cycleCount: number; startDate: string; cyclesRaised: number; cyclesPaid: number; prizeWon: boolean;
  prizedCycleNo?: number; dividendsReceived: number;
}
export interface Due {
  view: string; groupCode: string; groupName: string; ticketNo: number; ticketStatus: string; cycleNo: number; demandRef?: string; dueDate: string;
  daysOverdue: number; subscriptionOutstanding: number; lateFeeOutstanding: number; totalOutstanding: number;
}
export interface Payment {
  paymentRef: string; groupCode: string; groupName: string; ticketNo: number; amount: number; channel: string; status: string; businessDate: string;
}
export interface PaymentsPage { customer: boolean; overdue: Due[]; outstanding: Due[]; upcoming: Due[]; history: Payment[] }
export interface MemberRequest {
  kind: string; groupCode: string; groupName: string; ticketNo: number; status: string; inProgress: boolean; role: string; cycleNo?: number;
  amount?: number; held?: number; requestedAt?: string; decidedAt?: string;
}
export interface MyBid { ticketNo: number; bidSeq: number; discountAmount: number; status: string; leading?: boolean }
export interface Auction {
  groupCode: string; groupName: string; cycleNo: number; state: 'UPCOMING' | 'LIVE' | 'CLOSED'; auctionType: string; auctionDate: string;
  openedAt?: string; closedAt?: string; lastCycle: boolean; minDiscount: number; maxDiscount: number; highestBid?: number; bidsHidden: boolean;
  bidCount: number; myTickets: number[]; eligibleTickets: number[]; canBid: boolean; cannotBidReason?: string; myBids: MyBid[];
  iWon: boolean; winningTicketNo?: number; winningDiscount?: number; prize?: number;
}
export interface Offer {
  id: number; title: string; body: string; linkUrl?: string; imageUrl?: string; audience: string; startsOn: string; endsOn?: string;
}
export interface Overview {
  customer: boolean; unavailable: string[];
  summary: { ticketsHeld: number; nextPayment?: Due; overdueCount: number; overdueTotal: number; liveAuctions: number; upcomingAuctions: number; openRequests: number };
  nextPayments: Due[]; auctions: Auction[]; requests: MemberRequest[]; offers: Offer[]; tickets: Ticket[];
}
export interface BidResult { bidSeq: number; auctions: Auction[] }

export const api = {
  dashboard: () => call<Overview>('GET', '/me/dashboard'),
  tickets: () => call<Ticket[]>('GET', '/me/tickets'),
  payments: () => call<PaymentsPage>('GET', '/me/payments'),
  auctions: () => call<Auction[]>('GET', '/me/auctions'),
  requests: () => call<MemberRequest[]>('GET', '/me/requests'),
  publicOffers: () => call<Offer[]>('GET', '/offers/public'),
  placeBid: (b: { groupCode: string; cycleNo: number; ticketNo: number; discountAmount: number }, key: string) =>
    call<BidResult>('POST', '/me/bids', b, { 'Idempotency-Key': key }),
  withdrawBid: (b: { groupCode: string; cycleNo: number; bidSeq: number; reason?: string }, key: string) =>
    call<BidResult>('POST', '/me/bids/withdraw', b, { 'Idempotency-Key': key }),
  config: () => call<PortalConfig>('GET', '/config'),
  me: () => call<Member>('GET', '/me'),
  signIn: (idToken: string) => call<Member>('POST', '/auth/google', { idToken }),
  signOut: () => call<void>('POST', '/auth/logout'),
  invitation: (token: string) => call<Invitation>('GET', `/invitations/${encodeURIComponent(token)}`),
  accept: (token: string) => call<Invitation>('POST', `/invitations/${encodeURIComponent(token)}/accept`, {}),
  decline: (token: string) => call<Invitation>('POST', `/invitations/${encodeURIComponent(token)}/decline`, {}),
};
