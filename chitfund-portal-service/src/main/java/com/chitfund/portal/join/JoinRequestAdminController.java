package com.chitfund.portal.join;

import com.chitfund.portal.join.JoinRequestService.AdminView;
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

/** Staff review join requests here (admin credential only; see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/portal/admin/join-requests")
public class JoinRequestAdminController {

    public record Decision(String note) {
    }

    private final JoinRequestService service;
    private final Clock clock;

    public JoinRequestAdminController(JoinRequestService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public List<AdminView> list(@RequestParam(value = "status", required = false) String status) {
        return service.list(status);
    }

    @PostMapping("/{id}/approve")
    public AdminView approve(@PathVariable long id, @RequestBody(required = false) Decision body, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.decide(id, true, body == null ? null : body.note(), actor, OffsetDateTime.now(clock));
    }

    @PostMapping("/{id}/decline")
    public AdminView decline(@PathVariable long id, @RequestBody(required = false) Decision body, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.decide(id, false, body == null ? null : body.note(), actor, OffsetDateTime.now(clock));
    }
}
