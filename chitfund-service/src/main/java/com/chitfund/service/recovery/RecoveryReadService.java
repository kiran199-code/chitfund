package com.chitfund.service.recovery;

import com.chitfund.service.auction.AuctionModels.CoverRow;
import com.chitfund.service.calc.RecoveryWaterfall;
import com.chitfund.service.calc.RecoveryWaterfall.WriteOffAmounts;
import com.chitfund.service.collection.CollectionModels.DemandRow;
import com.chitfund.service.collection.CollectionRepository;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Money;
import com.chitfund.service.domain.ForemanGuaranteeMode;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;
import com.chitfund.service.enrolment.EnrolmentRepository;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.ledger.AccountRef;
import com.chitfund.service.ledger.AccountType;
import com.chitfund.service.ledger.LedgerRepository;
import com.chitfund.service.recovery.RecoveryDtos.ActionView;
import com.chitfund.service.recovery.RecoveryDtos.CoverView;
import com.chitfund.service.recovery.RecoveryDtos.Position;
import com.chitfund.service.recovery.RecoveryDtos.WriteOffView;
import com.chitfund.service.recovery.RecoveryModels.ActionRow;
import com.chitfund.service.recovery.RecoveryModels.CaseRow;
import com.chitfund.service.recovery.RecoveryModels.WriteOffRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Read side of recovery: a ticket's position, its recovery steps and the write-offs of a group. */
@Service
public class RecoveryReadService {

    private final GroupService groups;
    private final RecoveryRepository repo;
    private final EnrolmentRepository tickets;
    private final CollectionRepository collection;
    private final LedgerRepository ledgerRepo;

    public RecoveryReadService(GroupService groups, RecoveryRepository repo, EnrolmentRepository tickets, CollectionRepository collection,
                               LedgerRepository ledgerRepo) {
        this.groups = groups;
        this.repo = repo;
        this.tickets = tickets;
        this.collection = collection;
        this.ledgerRepo = ledgerRepo;
    }

    @Transactional(readOnly = true)
    public Position position(String code, int ticketNo, LocalDate today) {
        GroupRow g = groups.require(code);
        TicketRow t = tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + ticketNo));
        CaseRow c = repo.openCase(t.id()).or(() -> repo.latestCase(t.id())).orElse(null);
        List<DemandRow> overdue = collection.openDemandsOfTicket(t.id()).stream().filter(d -> d.dueDate().isBefore(today)).toList();
        BigDecimal sub = Money.ZERO;
        BigDecimal fees = Money.ZERO;
        for (DemandRow d : overdue) {
            sub = sub.add(d.figures().subscriptionOutstanding());
            fees = fees.add(d.figures().lateFeeOutstanding());
        }
        List<CoverRow> open = repo.openCovers(t.id());
        BigDecimal coversOwed = open.stream().map(x -> x.amount().subtract(x.recovered())).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal held = Money.max(Money.ZERO, ledgerRepo.balanceOf(g.id(), AccountRef.of(AccountType.SECURITY_DEPOSIT_LIABILITY, t.id())));

        String blocked = null;
        if (c == null || !"OPEN".equals(c.status()) || t.status() != TicketStatus.DEFAULTED) {
            blocked = "The ticket has no open default case";
        } else if (groups.rulesOf(g).foremanGuaranteeMode() == ForemanGuaranteeMode.NONE) {
            blocked = "The group has no foreman guarantee mode, so nobody carries a written-off loss";
        } else if (held.signum() > 0) {
            blocked = "The held deposit has not been applied yet";
        } else if (RecoveryWaterfall.writeOffAmounts(overdue.stream().map(DemandRow::figures).toList(),
                open.stream().map(x -> new RecoveryWaterfall.CoverBalance(x.id(), x.amount().subtract(x.recovered()))).toList()).isEmpty()) {
            blocked = "Nothing is owed";
        } else if (repo.pendingWriteOffExists(c.id())) {
            blocked = "A write-off is already waiting for a decision";
        }
        return new Position(g.groupCode(), t.ticketNo(), t.customerId(), t.status().name(), today, c == null ? null : c.id(), c == null ? null : c.status(),
                c == null ? null : c.openedOn(), Money.scale(sub), Money.scale(fees), Money.scale(coversOwed), Money.scale(held),
                c == null ? Money.ZERO : c.securityApplied(), c == null ? Money.ZERO : c.coverRecovered(), c == null ? Money.ZERO : c.writtenOff(),
                blocked == null, blocked, repo.coversOfTicket(t.id()).stream().map(RecoveryReadService::coverView).toList(),
                repo.actionsOfTicket(t.id()).stream().map(RecoveryReadService::actionView).toList(),
                repo.writeOffsOfTicket(t.id()).stream().map(w -> writeOffView(g.groupCode(), w)).toList());
    }

    @Transactional(readOnly = true)
    public ActionView action(String code, int ticketNo, long actionId) {
        GroupRow g = groups.require(code);
        TicketRow t = tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + ticketNo));
        return repo.actionsOfTicket(t.id()).stream().filter(a -> a.id() == actionId).findFirst().map(RecoveryReadService::actionView)
                .orElseThrow(() -> ChitException.notFound("Recovery step", code + " #" + ticketNo + "/" + actionId));
    }

    @Transactional(readOnly = true)
    public WriteOffView writeOff(String code, long id) {
        GroupRow g = groups.require(code);
        return writeOffView(g.groupCode(), repo.findWriteOff(g.id(), id).orElseThrow(() -> ChitException.notFound("Write-off", code + "/" + id)));
    }

    @Transactional(readOnly = true)
    public List<WriteOffView> writeOffs(String code, String status) {
        GroupRow g = groups.require(code);
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return repo.writeOffsOfGroup(g.id(), st).stream().map(w -> writeOffView(g.groupCode(), w)).toList();
    }

    static CoverView coverView(CoverRow c) {
        return new CoverView(c.id(), c.cycleNo(), c.amount(), c.recovered(), c.amount().subtract(c.recovered()), c.status(), c.coveredOn());
    }

    static ActionView actionView(ActionRow a) {
        return new ActionView(a.id(), a.ticketNo(), a.kind(), a.arrears(), a.guarantee(), a.arrears().add(a.guarantee()), a.channel(), a.externalRef(),
                a.paymentRef(), a.businessDate(), a.createdBy(), a.createdAt(), false);
    }

    static WriteOffView writeOffView(String code, WriteOffRow w) {
        return new WriteOffView(w.id(), code, w.ticketNo(), w.status(), w.subscription(), w.covers(), w.fees(), w.total(), w.reason(), w.madeBy(),
                w.checkedBy(), w.decisionReason(), w.requestedOn(), w.decidedOn(), w.createdAt(), w.decidedAt(), false);
    }
}
