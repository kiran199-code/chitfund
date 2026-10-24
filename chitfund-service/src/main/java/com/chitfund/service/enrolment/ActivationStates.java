package com.chitfund.service.enrolment;

import com.chitfund.service.enrolment.ActivationCheck.SecurityState;
import com.chitfund.service.enrolment.ActivationCheck.TicketState;
import com.chitfund.service.enrolment.EnrolmentModels.SecurityRow;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the live tickets of a group with the state of their security row, in the form {@link ActivationCheck} wants. */
final class ActivationStates {

    private ActivationStates() {
    }

    static List<TicketState> of(EnrolmentRepository repo, long groupId) {
        Map<Long, SecurityRow> security = new HashMap<>();
        for (SecurityRow s : repo.liveSecurityOfGroup(groupId)) {
            security.put(s.ticketId(), s);
        }
        List<TicketState> out = new ArrayList<>();
        for (TicketRow t : repo.tickets(groupId, false)) {
            SecurityRow s = security.get(t.id());
            SecurityState state = s == null ? SecurityState.NONE : "VERIFIED".equals(s.status()) ? SecurityState.VERIFIED : SecurityState.HELD;
            out.add(new TicketState(t.ticketNo(), state));
        }
        return out;
    }
}
