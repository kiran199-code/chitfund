package com.chitfund.service.transfer;

import com.chitfund.service.businessdate.BusinessDateService;
import com.chitfund.service.common.Actor;
import com.chitfund.service.transfer.TransferDtos.ExitRequest;
import com.chitfund.service.transfer.TransferDtos.RejectTransferRequest;
import com.chitfund.service.transfer.TransferDtos.TransferRequest;
import com.chitfund.service.transfer.TransferDtos.TransferView;
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
 * Ticket transfers and replacement exits, with maker-checker. Every call that changes something needs {@code Idempotency-Key} and
 * {@code X-Actor}; the business date comes from this service, never from the caller.
 */
@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Transfer", description = "Hand a ticket to another customer, or let a non-prized member leave through a replacement")
public class TransferController {

    private final TransferService transfers;
    private final TransferReadService reads;
    private final BusinessDateService dates;

    public TransferController(TransferService transfers, TransferReadService reads, BusinessDateService dates) {
        this.transfers = transfers;
        this.reads = reads;
        this.dates = dates;
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a transfer of the ticket to another customer (maker) (chit.transfer.request)",
            description = "Refused while the ticket has arrears (422 TRANSFER_ARREARS_EXIST), a prize waiting to be paid (409 TRANSFER_PAYOUT_PENDING) or another transfer pending "
                    + "(409 TRANSFER_PENDING). The group's transfer fee applies. An ACTIVE or PRIZED ticket is TRANSFERRED until the decision. Needs Idempotency-Key and X-Actor.")
    public TransferView transfer(@PathVariable String code, @PathVariable int ticketNo, @RequestBody TransferRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return transfers.requestTransfer(code, ticketNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/exit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request the exit of a non-prized member through a replacement subscriber (maker) (chit.transfer.request)",
            description = "The replacement takes the same ticket; there is no pool refund and no fee. 409 EXIT_PRIZED_TICKET for a ticket that has won. Needs Idempotency-Key and X-Actor.")
    public TransferView exit(@PathVariable String code, @PathVariable int ticketNo, @RequestBody ExitRequest request,
                             @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return transfers.requestExit(code, ticketNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @GetMapping("/groups/{code}/transfers")
    @Operation(summary = "Transfers and replacement exits of the group, newest first; status PENDING|APPROVED|REJECTED (chit.transfer.read)")
    public List<TransferView> transfers(@PathVariable String code, @RequestParam(value = "status", required = false) String status) {
        return reads.transfers(code, status);
    }

    @GetMapping("/groups/{code}/tickets/{ticketNo}/transfers")
    @Operation(summary = "The transfer history of one ticket (chit.transfer.read)")
    public List<TransferView> transfersOfTicket(@PathVariable String code, @PathVariable int ticketNo) {
        return reads.transfersOfTicket(code, ticketNo);
    }

    @GetMapping("/groups/{code}/transfers/{id}")
    @Operation(summary = "One transfer (chit.transfer.read)")
    public TransferView transfer(@PathVariable String code, @PathVariable long id) {
        return reads.transfer(code, id);
    }

    @PostMapping("/groups/{code}/transfers/{id}/approve")
    @Operation(summary = "Approve a transfer or exit (checker; must differ from the requester) (chit.transfer.approve)",
            description = "Changes the holder, posts the transfer fee and returns the ticket to ACTIVE or PRIZED. Needs Idempotency-Key and X-Actor.")
    public TransferView approve(@PathVariable String code, @PathVariable long id, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return transfers.approve(code, id, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/transfers/{id}/reject")
    @Operation(summary = "Reject a transfer or exit with a reason (checker) (chit.transfer.approve)",
            description = "The ticket goes back to the status it had. Needs Idempotency-Key and X-Actor.")
    public TransferView reject(@PathVariable String code, @PathVariable long id, @RequestBody RejectTransferRequest request,
                               @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return transfers.reject(code, id, request, idempotencyKey, Actor.required(actor));
    }
}
