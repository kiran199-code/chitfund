package com.chitfund.service.auction;

import com.chitfund.service.calc.AllocationEngine;
import com.chitfund.service.calc.AllocationEngine.DemandDue;
import com.chitfund.service.calc.AllocationEngine.Line;
import com.chitfund.service.calc.DemandFigures;
import com.chitfund.service.collection.CollectionModels.DemandRow;
import com.chitfund.service.collection.CollectionRepository;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Money;
import com.chitfund.service.domain.AllocationBucket;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money that moves inside the platform and reaches a ticket's account as a payment on channel INTERNAL: a foreman covering
 * an unpaid instalment, and a dividend credited to a subscriber's balance. Recording them as payments keeps demands,
 * advance and statements one mechanism. Callers cannot submit the channel and a payment on it cannot be reversed.
 */
@Component
public class InternalPayments {

    public static final String CHANNEL = "INTERNAL";

    private final CollectionRepository collection;
    private final AuctionRepository auctions;
    private final LedgerService ledger;

    public InternalPayments(CollectionRepository collection, AuctionRepository auctions, LedgerService ledger) {
        this.collection = collection;
        this.auctions = auctions;
        this.ledger = ledger;
    }

    /**
     * The foreman pays an unpaid instalment into the pool. The demand's subscription is marked paid, the ticket now owes the
     * foreman (FOREMAN_GUARANTEE_RECEIVABLE) and a cover row records it for recovery.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String coverInstalment(long groupId, long cycleId, int cycleNo, long ticketId, int ticketNo, long demandId, BigDecimal amount,
                                  LocalDate today, String actor) {
        DemandRow demand = collection.findDemand(demandId).orElseThrow(() -> ChitException.notFound("Demand", String.valueOf(demandId)));
        long id = collection.nextPaymentId();
        String ref = "RCPT-" + id;
        collection.insertPayment(id, ref, groupId, ticketId, amount, CHANNEL, today, "FCOVER:" + demandId, "FOREMAN COVER cycle " + cycleNo, actor);
        collection.insertAllocation(id, demandId, AllocationBucket.CURRENT, amount, false);
        collection.saveFigures(demandId, demand.figures().paySubscription(amount));
        ledger.post(JournalTemplates.foremanCovers("FCOVER:" + demandId, ticketId, amount),
                new PostingContext(groupId, today, cycleId, "FOREMAN_COVER", ref, actor));
        auctions.insertCover(cycleId, ticketId, demandId, id, amount, today, actor);
        return ref;
    }

    /**
     * CREDIT_SUBSCRIBER_BALANCE: the dividend is set against what the ticket owes now, in the group's allocation order, and any
     * remainder is held as advance for the next instalments. Returns the amount that went to open dues; the rest is advance.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal creditDividend(long groupId, long cycleId, int cycleNo, long dividendId, long ticketId, BigDecimal amount,
                                     List<AllocationBucket> order, LocalDate today, String actor) {
        List<DemandRow> open = collection.openDemandsOfTicket(ticketId);
        List<DemandDue> dues = open.stream().map(d -> new DemandDue(d.id(), d.cycleNo(), d.figures().subscriptionOutstanding(),
                d.figures().lateFeeOutstanding())).toList();
        AllocationEngine.Result alloc = AllocationEngine.allocate(amount, collection.latestStartedCycleNo(groupId), dues, order);
        long id = collection.nextPaymentId();
        String ref = "RCPT-" + id;
        collection.insertPayment(id, ref, groupId, ticketId, amount, CHANNEL, today, "DIVCREDIT:" + dividendId, "DIVIDEND cycle " + cycleNo, actor);
        Map<Long, DemandFigures> changed = new LinkedHashMap<>();
        Map<Long, DemandRow> rows = new LinkedHashMap<>();
        open.forEach(d -> rows.put(d.id(), d));
        for (Line line : alloc.lines()) {
            collection.insertAllocation(id, line.demandId(), line.bucket(), line.amount(), false);
            if (line.demandId() == null) {
                continue;
            }
            DemandFigures f = changed.getOrDefault(line.demandId(), rows.get(line.demandId()).figures());
            f = line.bucket() == AllocationBucket.LATE_FEES ? f.payLateFee(line.amount()) : f.paySubscription(line.amount());
            changed.put(line.demandId(), f);
        }
        changed.forEach(collection::saveFigures);
        ledger.post(JournalTemplates.dividendApplied("DIVCREDIT:" + dividendId, ticketId, amount),
                new PostingContext(groupId, today, cycleId, "DIVIDEND", ref, actor));
        return Money.scale(alloc.allocated());
    }
}
