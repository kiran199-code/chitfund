package com.chitfund.service.transfer;

import com.chitfund.service.auction.AuctionModels.CoverRow;
import com.chitfund.service.collection.CollectionModels.DemandRow;
import com.chitfund.service.collection.CollectionRepository;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Hashing;
import com.chitfund.service.common.Json;
import com.chitfund.service.common.Money;
import com.chitfund.service.config.ChitSettings;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.state.Lifecycles;
import com.chitfund.service.enrolment.EnrolmentModels.SecurityRow;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;
import com.chitfund.service.enrolment.EnrolmentRepository;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.idempotency.IdempotencyCheck.Replay;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerService;
import com.chitfund.service.recovery.RecoveryModels.CaseRow;
import com.chitfund.service.recovery.RecoveryRepository;
import com.chitfund.service.transfer.TransferDtos.ExitRequest;
import com.chitfund.service.transfer.TransferDtos.RejectTransferRequest;
import com.chitfund.service.transfer.TransferDtos.TransferRequest;
import com.chitfund.service.transfer.TransferDtos.TransferView;
import com.chitfund.service.transfer.TransferEligibility.Facts;
import com.chitfund.service.transfer.TransferModels.TransferRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Hands a ticket to another customer, either as a plain transfer (optional fee) or, for a non-prized member who leaves, as an
 * exit through a replacement subscriber. Both are the same movement and share one maker-checker flow.
 *
 * <p>The ticket stays the same record: its history, cycles, demands and security stay with it and only the holder changes (the
 * subscriber link is closed and a new one opened, so the full history of holders is kept). While a request waits for its
 * decision an ACTIVE or PRIZED ticket is TRANSFERRED, which keeps it from bidding or being paid a prize; a DEFAULTED ticket stays
 * DEFAULTED. Arrears must be cleared or written off first. Nothing moves in the books except the transfer fee, which is posted
 * when the transfer is approved.
 */
@Service
public class TransferService {

    private static final String OP_TRANSFER = "TRANSFER_REQUEST";
    private static final String OP_EXIT = "EXIT_REQUEST";
    private static final String OP_APPROVE = "TRANSFER_APPROVE";
    private static final String OP_REJECT = "TRANSFER_REJECT";

    private final GroupService groups;
    private final TransferRepository repo;
    private final EnrolmentRepository tickets;
    private final CollectionRepository collection;
    private final RecoveryRepository recovery;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final ChitSettings settings;
    private final TransferReadService reads;

    public TransferService(GroupService groups, TransferRepository repo, EnrolmentRepository tickets, CollectionRepository collection,
                           RecoveryRepository recovery, LedgerService ledger, IdempotencyStore idempotency, Audit audit, ChitSettings settings,
                           TransferReadService reads) {
        this.groups = groups;
        this.repo = repo;
        this.tickets = tickets;
        this.collection = collection;
        this.recovery = recovery;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.reads = reads;
    }

    // ---- request (maker) ------------------------------------------------------------------------------------------

    @Transactional
    public TransferView requestTransfer(String code, int ticketNo, TransferRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        return request(code, ticketNo, TransferKind.TRANSFER, req.toCustomerId(), "toCustomerId", req.reason(), req.payoutAccountRef(), OP_TRANSFER,
                idemKey, actor, today);
    }

    @Transactional
    public TransferView requestExit(String code, int ticketNo, ExitRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        return request(code, ticketNo, TransferKind.REPLACEMENT, req.replacementCustomerId(), "replacementCustomerId", req.reason(), req.payoutAccountRef(),
                OP_EXIT, idemKey, actor, today);
    }

    private TransferView request(String code, int ticketNo, TransferKind kind, Long toCustomerId, String toField, String reasonRaw, String accountRaw,
                                 String op, String idemKey, String actor, LocalDate today) {
        if (toCustomerId == null || toCustomerId <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", toField + " is required");
        }
        String reason = reasonRaw == null ? "" : reasonRaw.trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 200 characters");
        }
        String account = accountRaw == null || accountRaw.isBlank() ? null : accountRaw.trim();
        if (account != null && account.length() > 60) {
            throw ChitException.badRequest("VALIDATION_FAILED", "payoutAccountRef is longer than 60 characters");
        }
        Replay replay = idempotency.begin(idemKey, op, Hashing.sha256(code + "|" + ticketNo + "|" + kind + "|" + toCustomerId + "|" + reason + "|" + (account == null ? "" : account)));
        if (replay != null) {
            return reads.transfer(code, Long.parseLong(replay.resultRef())).asReplay();
        }
        GroupRow g = groups.lock(code);
        ChitRules rules = groups.rulesOf(g);
        TicketRow t = tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + ticketNo));
        TransferEligibility.check(kind, facts(g, t, t.status(), toCustomerId, repo.pendingExists(t.id()), today));

        BigDecimal fee = kind == TransferKind.TRANSFER ? Money.scale(Money.nz(rules.transferFee())) : Money.ZERO;
        long id = repo.insert(t.id(), t.customerId(), toCustomerId, fee, kind, reason, account, t.status().name(), today, actor);
        if (t.status() == TicketStatus.ACTIVE || t.status() == TicketStatus.PRIZED) {
            Lifecycles.TICKET.require(t.status(), TicketStatus.TRANSFERRED);
            tickets.setTicketStatus(t.id(), TicketStatus.TRANSFERRED, actor);
        }
        audit.log("TRANSFER", code + "#" + ticketNo + "/" + id, "REQUESTED", actor, kind + " from customer " + t.customerId() + " to " + toCustomerId + ": " + reason);
        audit.event("TicketTransferRequested", "TICKET", code + "/" + ticketNo,
                Json.obj("groupCode", code, "ticketNo", ticketNo, "transferId", id, "kind", kind.name(), "fromCustomerId", t.customerId(), "toCustomerId", toCustomerId,
                        "fee", fee.signum() > 0 ? fee : null, "reason", reason, "madeBy", actor, "businessDate", today.toString()));
        idempotency.complete(idemKey, String.valueOf(id));
        return reads.transfer(code, id);
    }

    // ---- approve / reject (checker) -------------------------------------------------------------------------------

    @Transactional
    public TransferView approve(String code, long transferId, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_APPROVE, Hashing.sha256(code + "|" + transferId));
        if (replay != null) {
            return reads.transfer(code, transferId).asReplay();
        }
        GroupRow g = groups.lock(code);
        TransferRow x = pending(g, transferId);
        requireChecker(x, actor);
        TicketRow t = tickets.findById(x.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(x.ticketId())));
        if (t.customerId() != x.fromCustomerId()) {
            throw ChitException.conflict("DATA_CONFLICT", "Ticket " + t.ticketNo() + " is no longer held by customer " + x.fromCustomerId() + "; ask again");
        }
        TicketStatus before = TicketStatus.valueOf(x.previousStatus());
        TransferEligibility.check(x.kind(), facts(g, t, before, x.toCustomerId(), false, today));

        boolean wasDefaulted = before == TicketStatus.DEFAULTED;
        TicketStatus after = wasDefaulted ? Lifecycles.restoreAfterDefault(collection.hasWonPrize(t.id())) : before;
        Lifecycles.TICKET.require(wasDefaulted ? TicketStatus.DEFAULTED : TicketStatus.TRANSFERRED, after);

        tickets.closeLink(t.id(), today);
        tickets.insertLink(t.id(), x.toCustomerId(), today, x.kind() == TransferKind.REPLACEMENT ? "REPLACEMENT" : "TRANSFER", actor);
        tickets.reassignTicket(t.id(), x.toCustomerId(), x.payoutAccountRef(), actor);
        tickets.setTicketStatus(t.id(), after, actor);

        String caseStatus = null;
        if (wasDefaulted) {
            CaseRow c = recovery.openCaseForUpdate(t.id()).orElse(null);
            if (c != null) {
                caseStatus = c.writtenOff().signum() > 0 ? "WRITTEN_OFF" : c.recovered().signum() > 0 ? "RECOVERED" : "CURED";
                collection.closeCase(c.id(), caseStatus, today);
            }
        }
        if (x.fee().signum() > 0) {
            ledger.post(JournalTemplates.transferFee("TRANSFERFEE:" + x.id(), x.fee()),
                    new PostingContext(g.id(), today, null, "TRANSFER", "TRANSFER-" + x.id(), actor));
        }
        repo.approve(x.id(), actor, today);
        audit.log("TRANSFER", code + "#" + t.ticketNo() + "/" + x.id(), "APPROVED", actor, x.kind() + " to customer " + x.toCustomerId() + ", made by " + x.madeBy());
        audit.event("TicketTransferred", "TICKET", code + "/" + t.ticketNo(),
                Json.obj("groupCode", code, "ticketNo", t.ticketNo(), "transferId", x.id(), "kind", x.kind().name(), "fromCustomerId", x.fromCustomerId(),
                        "toCustomerId", x.toCustomerId(), "fee", x.fee().signum() > 0 ? x.fee() : null, "ticketStatus", after.name(),
                        "defaultCaseClosedAs", caseStatus, "effectiveOn", today.toString(), "madeBy", x.madeBy(), "checkedBy", actor));
        idempotency.complete(idemKey, String.valueOf(x.id()));
        return reads.transfer(code, x.id());
    }

    @Transactional
    public TransferView reject(String code, long transferId, RejectTransferRequest req, String idemKey, String actor) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 200 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_REJECT, Hashing.sha256(code + "|" + transferId + "|" + reason));
        if (replay != null) {
            return reads.transfer(code, transferId).asReplay();
        }
        GroupRow g = groups.lock(code);
        TransferRow x = pending(g, transferId);
        requireChecker(x, actor);
        TicketRow t = tickets.findById(x.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(x.ticketId())));
        TicketStatus before = TicketStatus.valueOf(x.previousStatus());
        if (t.status() == TicketStatus.TRANSFERRED) {
            Lifecycles.TICKET.require(TicketStatus.TRANSFERRED, before);
            tickets.setTicketStatus(t.id(), before, actor);
        }
        repo.reject(x.id(), actor, reason);
        audit.log("TRANSFER", code + "#" + t.ticketNo() + "/" + x.id(), "REJECTED", actor, reason);
        audit.event("TicketTransferRejected", "TICKET", code + "/" + t.ticketNo(),
                Json.obj("groupCode", code, "ticketNo", t.ticketNo(), "transferId", x.id(), "kind", x.kind().name(), "reason", reason, "madeBy", x.madeBy(),
                        "checkedBy", actor, "ticketStatus", before.name()));
        idempotency.complete(idemKey, String.valueOf(x.id()));
        return reads.transfer(code, x.id());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Everything {@link TransferEligibility} needs, read from the database. */
    private Facts facts(GroupRow g, TicketRow t, TicketStatus ticketStatus, long newCustomerId, boolean otherPending, LocalDate today) {
        BigDecimal overdue = Money.ZERO;
        for (DemandRow d : collection.openDemandsOfTicket(t.id())) {
            if (d.dueDate().isBefore(today)) {
                overdue = overdue.add(d.figures().totalOutstanding());
            }
        }
        BigDecimal covers = Money.ZERO;
        for (CoverRow c : recovery.openCovers(t.id())) {
            covers = covers.add(c.amount().subtract(c.recovered()));
        }
        Long guarantor = tickets.liveSecurityOfTicket(t.id()).filter(s -> "GUARANTOR".equals(s.kind())).map(SecurityRow::guarantorCustomerId).orElse(null);
        return new Facts(g.status(), ticketStatus, collection.hasWonPrize(t.id()), t.customerId(), newCustomerId, guarantor, Money.scale(overdue),
                Money.scale(covers), repo.prizePayoutInProgress(t.id()), otherPending);
    }

    private TransferRow pending(GroupRow g, long transferId) {
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + g.groupCode() + " is " + g.status() + "; transfers are decided while it is ACTIVE or CLOSING");
        }
        TransferRow x = repo.findForUpdate(g.id(), transferId).orElseThrow(() -> ChitException.notFound("Transfer", g.groupCode() + "/" + transferId));
        if (!"PENDING".equals(x.status())) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Transfer " + transferId + " is " + x.status() + ", not waiting for a decision");
        }
        return x;
    }

    private void requireChecker(TransferRow x, String actor) {
        if (settings.makerCheckerEnforce() && actor.equals(x.madeBy())) {
            throw ChitException.forbidden("MAKER_CHECKER_VIOLATION", "The transfer was requested by " + x.madeBy() + "; a different person must decide on it");
        }
    }
}
