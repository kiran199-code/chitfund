package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.DividendPayResult;
import com.chitfund.service.auction.AuctionDtos.DividendView;
import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.auction.AuctionModels.DividendRow;
import com.chitfund.service.calc.PoolPosition;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Hashing;
import com.chitfund.service.common.Json;
import com.chitfund.service.common.Money;
import com.chitfund.service.domain.DividendMode;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.idempotency.IdempotencyCheck.Replay;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Paying a cycle's dividends in cash (dividend_mode PAY_OUT). The other modes need no step: ADJUST_NEXT_INSTALMENT is used by
 * the next demand run and CREDIT_SUBSCRIBER_BALANCE is credited to the ticket's account when the auction closes.
 *
 * <p>Cash is paid only if the pool can meet everything it owes afterwards, so a dividend never takes the cash a prize needs.
 */
@Service
public class DividendService {

    private static final String OP_PAY = "DIVIDEND_PAY";

    private final GroupService groups;
    private final AuctionRepository repo;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final AuctionReadService reads;
    private final CycleSettler settler;

    public DividendService(GroupService groups, AuctionRepository repo, LedgerService ledger, IdempotencyStore idempotency, Audit audit,
                           AuctionReadService reads, CycleSettler settler) {
        this.groups = groups;
        this.repo = repo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.reads = reads;
        this.settler = settler;
    }

    @Transactional
    public DividendPayResult payOut(String code, int cycleNo, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_PAY, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return result(code, cycleNo, 0, Money.ZERO).asReplay();
        }
        GroupRow g = groups.lock(code);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + code + " is " + g.status());
        }
        ChitRules rules = groups.rulesOf(g);
        if (rules.dividendMode() != DividendMode.PAY_OUT) {
            throw ChitException.conflict("DIVIDEND_MODE_MISMATCH", "This group's dividend mode is " + rules.dividendMode()
                    + "; only PAY_OUT dividends are paid in cash");
        }
        CycleInfo c = repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", code + " #" + cycleNo));
        if (c.winnerTicketId() == null) {
            throw ChitException.conflict("AUCTION_NOT_CLOSED", "Cycle " + cycleNo + " has no declared dividend yet; its auction is not closed");
        }
        List<DividendRow> due = repo.dividendsOfCycle(c.id()).stream().filter(d -> "DECLARED".equals(d.status())).toList();
        BigDecimal total = due.stream().map(DividendRow::amount).reduce(Money.ZERO, BigDecimal::add);
        if (!due.isEmpty()) {
            PoolPosition pos = reads.position(g.id());
            if (pos.surplus().signum() < 0) {
                throw new ChitException("PAYOUT_LIQUIDITY_SHORT", "The pool owes " + pos.surplus().negate().toPlainString()
                        + " more than it holds; dividends are paid in cash once it can meet everything it owes");
            }
            for (DividendRow d : due) {
                ledger.post(JournalTemplates.dividendPaidOut("DIVPAY:" + d.id(), d.ticketId(), d.amount()),
                        new PostingContext(g.id(), today, c.id(), "DIVIDEND", code + "#" + cycleNo, actor));
                repo.markDividendPaid(d.id(), today);
            }
            List<Integer> nos = new ArrayList<>();
            due.forEach(d -> nos.add(d.ticketNo()));
            audit.log("CYCLE", code + "#" + cycleNo, "DIVIDENDS_PAID", actor, due.size() + " dividend(s), " + total.toPlainString());
            audit.event("DividendPaid", "GROUP", code, Json.obj("groupCode", code, "cycleNo", cycleNo, "tickets", nos, "total", total,
                    "businessDate", today.toString(), "paidBy", actor));
            settler.settleIfDone(code, c, actor);
        }
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return result(code, cycleNo, due.size(), total);
    }

    private DividendPayResult result(String code, int cycleNo, int paid, BigDecimal total) {
        GroupRow g = groups.require(code);
        CycleInfo c = repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", code + " #" + cycleNo));
        List<DividendView> views = repo.dividendsOfCycle(c.id()).stream().map(AuctionReadService::dividendView).toList();
        return new DividendPayResult(code, cycleNo, paid, total, views, false);
    }
}
