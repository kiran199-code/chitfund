package com.chitfund.service.recovery;

import com.chitfund.service.businessdate.BusinessDateService;
import com.chitfund.service.common.Actor;
import com.chitfund.service.recovery.RecoveryDtos.ActionView;
import com.chitfund.service.recovery.RecoveryDtos.ApplySecurityRequest;
import com.chitfund.service.recovery.RecoveryDtos.Position;
import com.chitfund.service.recovery.RecoveryDtos.RejectWriteOffRequest;
import com.chitfund.service.recovery.RecoveryDtos.RepayCoverRequest;
import com.chitfund.service.recovery.RecoveryDtos.WriteOffRequest;
import com.chitfund.service.recovery.RecoveryDtos.WriteOffView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recovery of a defaulted ticket and write-offs. Every call that changes something needs {@code Idempotency-Key} and {@code X-Actor};
 * the business date comes from this service, never from the caller.
 */
@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Recovery", description = "Apply a defaulter's deposit, record repayments to the foreman, write off with maker-checker")
public class RecoveryController {

    private final RecoveryService recovery;
    private final RecoveryReadService reads;
    private final BusinessDateService dates;

    public RecoveryController(RecoveryService recovery, RecoveryReadService reads, BusinessDateService dates) {
        this.recovery = recovery;
        this.reads = reads;
        this.dates = dates;
    }

    @GetMapping("/groups/{code}/tickets/{ticketNo}/recovery")
    @Operation(summary = "Where the ticket stands in recovery: overdue, covers owed, deposit held, steps taken, write-offs (chit.recovery.read)")
    public Position position(@PathVariable String code, @PathVariable int ticketNo) {
        return reads.position(code, ticketNo, dates.today());
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/recovery/apply-security")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Apply the defaulter's held deposit: first to what the pool is owed, then to the foreman's covers (chit.recovery.apply)",
            description = "409 RECOVERY_TICKET_NOT_DEFAULTED, 422 NO_SECURITY_HELD (a lien or guarantor is claimed outside the books), 422 NOTHING_TO_RECOVER. "
                    + "Body is optional: {\"amount\": n} limits how much is applied. Needs Idempotency-Key and X-Actor.")
    public ActionView applySecurity(@PathVariable String code, @PathVariable int ticketNo, @RequestBody(required = false) ApplySecurityRequest request,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return recovery.applySecurity(code, ticketNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/recovery/repay-cover")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record money the holder repays to the foreman against his covers, oldest first (chit.recovery.apply)",
            description = "409 COVER_REPAYMENT_TOO_LARGE if more than the foreman is owed. Not a payment into the pool. Needs Idempotency-Key and X-Actor.")
    public ActionView repayCover(@PathVariable String code, @PathVariable int ticketNo, @RequestBody RepayCoverRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return recovery.repayCover(code, ticketNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/recovery/write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a write-off of what a defaulted ticket still owes (maker) (chit.writeoff.request)",
            description = "Refused while a deposit is still held (409 SECURITY_STILL_HELD) and for a group with no foreman guarantee mode (422 WRITE_OFF_NOT_PERMITTED). "
                    + "The foreman bears the loss. Needs Idempotency-Key and X-Actor.")
    public WriteOffView requestWriteOff(@PathVariable String code, @PathVariable int ticketNo, @RequestBody WriteOffRequest request,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return recovery.requestWriteOff(code, ticketNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @GetMapping("/groups/{code}/write-offs")
    @Operation(summary = "Write-offs of the group, newest first; status PENDING|APPROVED|REJECTED (chit.recovery.read)")
    public List<WriteOffView> writeOffs(@PathVariable String code, @RequestParam(value = "status", required = false) String status) {
        return reads.writeOffs(code, status);
    }

    @GetMapping("/groups/{code}/write-offs/{id}")
    @Operation(summary = "One write-off (chit.recovery.read)")
    public WriteOffView writeOff(@PathVariable String code, @PathVariable long id) {
        return reads.writeOff(code, id);
    }

    @PostMapping("/groups/{code}/write-offs/{id}/approve")
    @Operation(summary = "Approve a write-off (checker; must differ from the requester) (chit.writeoff.approve)",
            description = "409 WRITE_OFF_STALE if what the ticket owes has changed since the request: reject it and ask again. Needs Idempotency-Key and X-Actor.")
    public WriteOffView approveWriteOff(@PathVariable String code, @PathVariable long id, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestHeader(value = "X-Actor", required = false) String actor) {
        return recovery.approveWriteOff(code, id, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/write-offs/{id}/reject")
    @Operation(summary = "Reject a write-off with a reason (checker) (chit.writeoff.approve)", description = "Needs Idempotency-Key and X-Actor.")
    public WriteOffView rejectWriteOff(@PathVariable String code, @PathVariable long id, @RequestBody RejectWriteOffRequest request,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return recovery.rejectWriteOff(code, id, request, idempotencyKey, Actor.required(actor), dates.today());
    }
}
