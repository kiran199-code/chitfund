package com.chitfund.portal.invite;

import com.chitfund.portal.invite.InviteDtos.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Admin endpoints for invitations. Protected by the admin credential (HTTP Basic); never reachable with a member session. */
@RestController
@RequestMapping("/api/v1/portal/admin/groups/{code}/invitations")
public class InvitationAdminController {

    private final InvitationService service;
    private final Clock clock;

    public InvitationAdminController(InvitationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public BatchResult invite(@PathVariable String code, @RequestBody InviteRequest body,
                              @RequestHeader("Idempotency-Key") String idemKey, @RequestHeader("X-Actor") String actor) {
        return service.invite(code, body, idemKey, actor, null, OffsetDateTime.now(clock));
    }

    @GetMapping
    public List<InviteView> list(@PathVariable String code, @RequestParam(required = false) String status) {
        return service.list(code, status, OffsetDateTime.now(clock));
    }

    @PostMapping("/{id}/resend")
    public InviteView resend(@PathVariable String code, @PathVariable long id,
                             @RequestHeader("Idempotency-Key") String idemKey, @RequestHeader("X-Actor") String actor) {
        return service.resend(code, id, idemKey, actor, null, OffsetDateTime.now(clock));
    }

    @PostMapping("/{id}/revoke")
    public InviteView revoke(@PathVariable String code, @PathVariable long id, @RequestBody(required = false) RevokeRequest body,
                             @RequestHeader("X-Actor") String actor) {
        return service.revoke(code, id, body, actor, null, OffsetDateTime.now(clock));
    }
}
