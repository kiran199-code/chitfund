package com.chitfund.portal.offer;

import com.chitfund.portal.offer.OfferService.AdminOffer;
import com.chitfund.portal.offer.OfferService.OfferInput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Staff write offers here (admin credential only; see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/portal/admin/offers")
public class OfferAdminController {

    private final OfferService service;
    private final Clock clock;

    public OfferAdminController(OfferService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")));
    }

    @GetMapping
    public List<AdminOffer> list() {
        return service.adminList(today());
    }

    @PostMapping
    public AdminOffer create(@RequestBody OfferInput body, @RequestHeader("X-Actor") String actor) {
        return service.create(body, actor, today());
    }

    @PostMapping("/{id}")
    public AdminOffer update(@PathVariable long id, @RequestBody OfferInput body, @RequestHeader("X-Actor") String actor) {
        return service.update(id, body, actor, today());
    }

    @PostMapping("/{id}/hide")
    public AdminOffer hide(@PathVariable long id, @RequestHeader("X-Actor") String actor) {
        return service.setActive(id, false, actor, today());
    }

    @PostMapping("/{id}/show")
    public AdminOffer show(@PathVariable long id, @RequestHeader("X-Actor") String actor) {
        return service.setActive(id, true, actor, today());
    }
}
