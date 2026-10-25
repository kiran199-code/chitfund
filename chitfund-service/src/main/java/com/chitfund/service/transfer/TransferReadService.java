package com.chitfund.service.transfer;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;
import com.chitfund.service.enrolment.EnrolmentRepository;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.transfer.TransferDtos.TransferView;
import com.chitfund.service.transfer.TransferModels.TransferRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read side of transfers and replacement exits. */
@Service
public class TransferReadService {

    private final GroupService groups;
    private final TransferRepository repo;
    private final EnrolmentRepository tickets;

    public TransferReadService(GroupService groups, TransferRepository repo, EnrolmentRepository tickets) {
        this.groups = groups;
        this.repo = repo;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public TransferView transfer(String code, long id) {
        GroupRow g = groups.require(code);
        return view(g.groupCode(), repo.find(g.id(), id).orElseThrow(() -> ChitException.notFound("Transfer", code + "/" + id)));
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfers(String code, String status) {
        GroupRow g = groups.require(code);
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return repo.ofGroup(g.id(), st).stream().map(x -> view(g.groupCode(), x)).toList();
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfersOfTicket(String code, int ticketNo) {
        GroupRow g = groups.require(code);
        TicketRow t = tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + ticketNo));
        return repo.ofTicket(t.id()).stream().map(x -> view(g.groupCode(), x)).toList();
    }

    static TransferView view(String code, TransferRow x) {
        return new TransferView(x.id(), code, x.ticketNo(), x.kind().name(), x.status(), x.fromCustomerId(), x.toCustomerId(), x.fee(), x.reason(),
                x.decisionReason(), x.payoutAccountRef(), x.previousStatus(), x.requestedOn(), x.effectiveOn(), x.madeBy(), x.checkedBy(),
                x.createdAt(), x.decidedAt(), false);
    }
}
