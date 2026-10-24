package com.chitfund.service.enrolment;

import com.chitfund.service.calendar.CycleCalendarGenerator;
import com.chitfund.service.calendar.ScheduledCycle;
import com.chitfund.service.calendar.WorkingDayCalendar;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.enrolment.ActivationCheck.Blocker;
import com.chitfund.service.enrolment.ActivationCheck.TicketState;
import com.chitfund.service.enrolment.EnrolmentDtos.ActivationResult;
import com.chitfund.service.enrolment.EnrolmentDtos.Readiness;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Activation, in three steps so that no database lock or transaction is held while the calendar is being read:
 * (1) a repeated request is answered at once; (2) the group is read and checked, and the whole cycle calendar is built
 * from the working-day calendar; (3) {@link ActivationWriter} takes the lock, re-checks everything
 * against the then-current data and writes the cycles, accounts and status in one transaction. If the group changed
 * between steps 2 and 3 the write is refused (DATA_CONFLICT) and nothing is written; the caller simply repeats the call.
 */
@Service
public class ActivationService {

    /** What step 2 learnt and step 3 relies on. */
    public record Plan(String groupCode, long groupVersion, int rulesVersion, List<ScheduledCycle> cycles) {
    }

    private final EnrolmentRepository repo;
    private final GroupService groupService;
    private final ActivationWriter writer;

    public ActivationService(EnrolmentRepository repo, GroupService groupService, ActivationWriter writer) {
        this.repo = repo;
        this.groupService = groupService;
        this.writer = writer;
    }

    /** Not transactional on purpose (see the class comment). */
    public ActivationResult activate(String code, String idemKey, String actor, LocalDate today, WorkingDayCalendar calendar) {
        ActivationResult replay = writer.replayOf(code, idemKey);
        if (replay != null) {
            return replay;
        }
        return writer.write(prepare(code, calendar, today), idemKey, actor, today);
    }

    /** Whether the group could be activated now, and why not. Writes nothing. */
    public Readiness readiness(String code, LocalDate today, WorkingDayCalendar calendar) {
        GroupRow g = groupService.require(code);
        ChitRules rules = groupService.rulesOf(g);
        List<TicketState> states = ActivationStates.of(repo, g.id());
        List<Blocker> blockers = new ArrayList<>(ActivationCheck.blockers(new ActivationCheck.Input(g.status(), rules, states, today, null)));
        LocalDate firstDue = null;
        try {
            List<ScheduledCycle> cycles = CycleCalendarGenerator.generate(rules, calendar);
            firstDue = cycles.get(0).dueDate();
            if (firstDue.isBefore(today)) {
                blockers.add(new Blocker("FIRST_DUE_DATE_PASSED", "The first instalment date " + firstDue
                        + " is before the business date " + today + "; move the start date and activate again"));
            }
        } catch (ChitException e) {
            if (!e.code().startsWith("CALENDAR_")) {
                throw e;
            }
            blockers.add(new Blocker(e.code(), e.getMessage()));
        }
        return new Readiness(blockers.isEmpty(), blockers, firstDue);
    }

    Plan prepare(String code, WorkingDayCalendar calendar, LocalDate today) {
        GroupRow g = groupService.require(code);
        ChitRules rules = groupService.rulesOf(g);
        // Cheap checks first: refuse before reading the calendar.
        ActivationCheck.requireClear(new ActivationCheck.Input(g.status(), rules, ActivationStates.of(repo, g.id()), today, null));
        List<ScheduledCycle> cycles = CycleCalendarGenerator.generate(rules, calendar);
        return new Plan(code, g.version(), g.currentRulesVersion(), cycles);
    }
}
