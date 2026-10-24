package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.Json;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.state.Lifecycles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A cycle is SETTLED when its prize has been paid in full and every dividend meant to be paid out in cash has been paid.
 * Dividends set against later instalments stay declared until the demand run uses them; they do not hold the cycle open.
 */
@Component
public class CycleSettler {

    private final AuctionRepository repo;
    private final Audit audit;

    public CycleSettler(AuctionRepository repo, Audit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Settles the cycle if it is PAYOUT_DONE and nothing is left to pay out. Returns whether it is settled now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean settleIfDone(String groupCode, CycleInfo cycle, String actor) {
        CycleInfo fresh = repo.cycleById(cycle.id()).orElse(cycle);
        if (fresh.status() != CycleStatus.PAYOUT_DONE || repo.unpaidPayOutDividends(fresh.id()) > 0) {
            return false;
        }
        Lifecycles.CYCLE.require(CycleStatus.PAYOUT_DONE, CycleStatus.SETTLED);
        repo.settleCycle(fresh.id());
        audit.log("CYCLE", groupCode + "#" + fresh.cycleNo(), "SETTLED", actor, "prize paid, dividends settled");
        audit.event("CycleSettled", "GROUP", groupCode, Json.obj("groupCode", groupCode, "cycleNo", fresh.cycleNo()));
        return true;
    }
}
