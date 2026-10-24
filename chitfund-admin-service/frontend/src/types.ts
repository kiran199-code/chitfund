export interface Me { username: string; timezone: string; roles?: string[] }

/** What chitfund-service says about its own business date (through /api/admin/business-date). */
export interface BusinessDateView {
  businessDate: string; previousBusinessDate: string | null; nextBusinessDate: string | null;
  /** OPEN or EOD_RUNNING */
  status: string; workingDay: boolean; systemDate: string | null;
  /** MANAGED: the date moves only when end of day closes it. SYSTEM: the wall-clock date. */
  mode: string;
}
export interface Holiday { date: string; name: string; createdBy: string | null }
export interface CalendarView { weekend: string[]; holidays: Holiday[] }
export interface WorkingDayView { date: string; workingDay: boolean }

// ---------------------------------------------------------------- batch jobs and end of day (chitfund-batch-service)

export interface BatchJob {
  jobName: string; jobCategory: string; jobType: string; cronExpression: string | null; enabled: boolean; restartable: boolean;
  priority: number | null; timeoutMinutes: number | null; assembled: boolean;
  running: boolean | null; lastStatus: string | null; lastExitCode: string | null; lastRunStartTime: string | null; nextFireTime: string | null;
}
export interface BatchExecution {
  jobExecutionId: number; jobName: string; status: string; exitCode: string | null; businessDate: string | null;
  startTime: string | null; endTime: string | null; readCount: number; writeCount: number; skipCount: number;
}

export interface EodJobRow { jobName: string; status: string; startedAt: string | null; finishedAt: string | null; message: string | null }
export interface EodProgress {
  state: 'IDLE' | 'RUNNING' | 'ADVANCED' | 'FAILED';
  actor: string | null;
  businessDate: string | null;
  resumed: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  currentJob: string | null;
  total: number;
  done: number;
  jobs: EodJobRow[];
  newBusinessDate: string | null;
  message: string | null;
}
export interface EodBusinessDate {
  businessDate: string; previousBusinessDate: string | null; nextBusinessDate: string | null;
  status: string; workingDay: boolean; systemDate: string | null; mode?: string;
}
export interface EodStatus {
  /** Whether the admin service knows the batch service address and password. */
  configured: boolean;
  /** Whether the batch service answered. */
  reachable: boolean;
  error?: string;
  /** The business date as chitfund-service reports it, so it shows even while the batch service sleeps. */
  chitBusinessDate: string | null;
  chitStatus: string | null;
  chitMode?: string | null;
  chitError?: string;
  running?: boolean;
  businessDate?: EodBusinessDate | null;
  progress?: EodProgress;
}
export interface EodRunResult { started: boolean; message: string }

export interface MaintenanceBatch {
  configured: boolean;
  reachable?: boolean;
  resetEnabled?: boolean;
  busy?: boolean;
  busyReason?: string;
  rowCounts?: Record<string, number>;
  error?: string;
}
export interface MaintenanceStatus {
  resetEnabled: boolean;
  rowCounts: Record<string, number>;
  businessDate: string;
  businessDateStatus: string;
  businessDateMode: string | null;
  batch: MaintenanceBatch;
}
export interface ClearDataResult {
  deleted: Record<string, number>;
  totalRows: number;
  clearedBy: string;
  message: string;
  batch?: { configured: boolean; totalRows?: number };
}

// ---------------------------------------------------------------- chit funds (chitfund-service, through /api/admin/chit)

export interface ChitGroup {
  groupCode: string; name: string; foremanRef: string; status: string; chitValue: number; ticketCount: number; cycleCount: number;
  subscription: number; frequency: string; startDate: string; rulesVersion: number; ticketsEnrolled: number; seatsLeft: number;
  activatedAt: string | null; activatedBy: string | null; cancelledAt: string | null; cancelledBy: string | null;
  cancelReason: string | null; version: number; createdAt: string; createdBy: string; replayed?: boolean | null;
}
export interface ChitGroupPage { items: ChitGroup[]; total: number; limit: number; offset: number }
export interface ChitFee { kind: string; amount: number; status: string; receivedOn: string | null; refundedOn: string | null }
export interface ChitSecurity {
  kind: string; amount: number; guarantorCustomerId: number | null; reference: string | null; status: string;
  receivedOn: string | null; verifiedBy: string | null;
}
export interface ChitTicket {
  ticketId: number; ticketNo: number; customerId: number; status: string; payoutAccountRef: string | null; fees: ChitFee[];
  security: ChitSecurity | null; exitReason: string | null; exitedOn: string | null; replayed?: boolean | null;
}
export interface ChitQuote {
  enrolmentFee: number; registrationFee: number; securityDeposit: number; lienAmount: number; guarantorRequired: boolean; cashDue: number;
}
export interface ChitReadiness { ready: boolean; blockers: { code: string; message: string }[]; firstDueDate: string | null }
export interface ChitCycle { cycleNo: number; status: string; dueDate: string; auctionDate: string }
export interface ChitActivation { group: ChitGroup; cycles: ChitCycle[]; replayed?: boolean | null }
export interface ChitRefund { lines: { kind: string; ticketNo: number; amount: number }[]; cashRefunded: number }
export interface ChitWithdrawResult { ticket: ChitTicket; refund: ChitRefund; groupStatus: string; replayed?: boolean | null }
export interface ChitCancelResult { groupCode: string; status: string; reason: string; refund: ChitRefund; replayed?: boolean | null }
export interface ChitPreview {
  valid: boolean; violations: { code: string; field: string | null; message: string }[]; effectiveRules: Record<string, unknown> | null;
  schedule: { cycleNo: number; dueDate: string; auctionDate: string }[] | null; scheduleError: string | null;
}

// ---------------------------------------------------------------- chit collection: dues, payments, statements, daily runs

export interface ChitAllocation { cycleNo: number | null; demandRef: string | null; bucket: string; amount: number; fromAdvance: boolean }
export interface ChitPayment {
  paymentRef: string; groupCode: string; ticketNo: number; customerId: number; amount: number; channel: string; status: string;
  businessDate: string; externalRef: string | null; allocated: number; advance: number; allocations: ChitAllocation[] | null;
  reversalReason: string | null; reversedAt: string | null; reversedBy: string | null; createdAt: string; createdBy: string; replayed?: boolean | null;
}
export interface ChitDemand {
  demandRef: string; cycleNo: number; ticketNo: number; dueDate: string; raisedOn: string; subscriptionAmount: number; dividendAdjustment: number;
  subscriptionPaid: number; subscriptionOutstanding: number; lateFeeAccrued: number; lateFeePaid: number; lateFeeOutstanding: number;
  totalOutstanding: number; daysOverdue: number; status: string;
}
export interface ChitDefaultCase {
  ticketNo: number; customerId: number; status: string; openedOn: string; shortfall: number; reason: string | null;
  missedInstalments: number | null; daysOverdue: number | null; closedOn: string | null; foremanCovered: number | null; openedBy: string | null;
}
export interface ChitStatement {
  groupCode: string; ticketNo: number; customerId: number; ticketStatus: string; asOf: string; subscriptionOutstanding: number;
  lateFeeOutstanding: number; totalOutstanding: number; advanceHeld: number; totalPaid: number; nextDueDate: string | null;
  openDefaultCase: ChitDefaultCase | null; demands: ChitDemand[]; payments: ChitPayment[];
}
export interface ChitCycleLine {
  cycleNo: number; status: string; dueDate: string; demands: number; settledDemands: number; due: number; collected: number;
  outstanding: number; collectedPct: number; lateFeeAccrued: number; lateFeePaid: number;
}
export interface ChitDefaulterLine {
  ticketNo: number; customerId: number; subscriptionOutstanding: number; lateFeeOutstanding: number; overdueInstalments: number; oldestDaysOverdue: number;
}
export interface ChitGroupStatement {
  groupCode: string; groupName: string; asOf: string; cycles: ChitCycleLine[]; totalDue: number; totalCollected: number;
  totalOutstanding: number; lateFeeOutstanding: number; overdueTickets: ChitDefaulterLine[];
}
export interface ChitDue {
  view: string; groupCode: string; groupName: string; ticketNo: number; customerId: number; ticketStatus: string; cycleNo: number;
  demandRef: string | null; dueDate: string; daysOverdue: number; subscriptionOutstanding: number; lateFeeOutstanding: number; totalOutstanding: number;
}
export interface ChitDuesPage { view: string; asOf: string; rows: ChitDue[]; limit: number; offset: number }
export interface ChitLateFeePreview {
  groupCode: string; ticketNo: number; from: string; days: number; rule: string;
  demands: { demandRef: string; cycleNo: number; dueDate: string; overdue: number; accruedSoFar: number; days: { date: string; accrual: number; cumulative: number }[] }[];
}
export interface ChitRunSummary {
  job: string; businessDate: string; groupsProcessed: number; groupsFailed: number; figures: Record<string, number>;
  failures: { groupCode: string; code: string; message: string }[];
}

// ---- Phase 5: auctions, payouts, dividends -------------------------------------------------------------------------------
export interface ChitBid {
  bidSeq: number; ticketNo: number; discountAmount?: number | null; bidTime: string; placedBy: string; status: string;
  withdrawnReason?: string | null; winning: boolean; replayed?: boolean | null;
}
/** The seed is text: it is a 64-bit number, which a browser cannot hold exactly. */
export interface ChitDraw {
  id: number; purpose: string; algorithm: string; seed: string; candidates: number[]; result: number[]; createdAt: string; createdBy: string;
}
export interface ChitDividend {
  cycleNo: number; ticketNo: number; amount: number; mode: string; status: string; declaredOn?: string | null; settledOn?: string | null;
}
export interface ChitSettlement {
  chitValue: number; discount: number; prize: number; commission: number; dividendPool: number; balanced: boolean; dividends: ChitDividend[];
}
export interface ChitAuction {
  groupCode: string; cycleNo: number; cycleStatus: string; dueDate: string; auctionDate: string; auctionType: string; lastCycle: boolean;
  attempt?: number | null; status: string; openedAt?: string | null; openedBy?: string | null; closedAt?: string | null; closedBy?: string | null;
  closeBasis?: string | null; gateNote?: string | null; minDiscount: number; maxDiscount: number; highestBid?: number | null; bidCount: number;
  bidsHidden: boolean; bids: ChitBid[]; winnerTicketNo?: number | null; winnerCustomerId?: number | null; settlement?: ChitSettlement | null;
  draw?: ChitDraw | null; rolledOverAttempts?: number[] | null; replayed?: boolean | null;
}
export interface ChitGate {
  groupCode: string; cycleNo: number; cycleStatus: string; auctionDate: string; rule: string; open: boolean; due: boolean; reason: string;
  dueAmount?: number | null; collected?: number | null; collectedPct?: number | null; shortfall?: number | null;
  unpaid?: { ticketNo: number; amount: number }[] | null; lastCycle: boolean; blocker?: string | null;
}
export interface ChitPayoutLeg {
  legNo: number; kind: string; amount: number; holdAmount: number; instructionRef?: string | null; businessDate: string; createdAt: string; createdBy: string;
}
export interface ChitPayout {
  groupCode: string; cycleNo: number; ticketNo: number; customerId: number; grossPrize: number; holdAmount: number; netAmount: number; status: string;
  madeBy: string; checkedBy?: string | null; decisionNote?: string | null; approvedAt?: string | null; payoutRef?: string | null; accountRef?: string | null;
  disbursedAt?: string | null; disbursedBy?: string | null; paidGross: number; paidHold: number; remainingGross: number; floatAmount: number;
  floatRepaid: number; legs: ChitPayoutLeg[]; replayed?: boolean | null;
}
export interface ChitLiquidityOutcome {
  rule: string; configured: boolean; action: string; payNow: number; floatAmount: number; balanceLater: number; shortfall: number;
}
export interface ChitPoolPosition {
  poolCash: number; commissionIncome: number; lateFeeIncome: number; dividendPayable: number; prizePayable: number; advanceHeld: number;
  availableForPrize: number; surplus: number; prizeRemaining?: number | null; outcomes: ChitLiquidityOutcome[];
}
export interface ChitDividendPayResult { groupCode: string; cycleNo: number; paid: number; total: number; dividends: ChitDividend[]; replayed?: boolean | null }
export interface ChitCover { cycleNo: number; ticketNo: number; amount: number; recovered: number; status: string; coveredOn: string }
