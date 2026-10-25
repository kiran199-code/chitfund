package com.chitfund.portal.offer;

import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.member.OfferSource;
import com.chitfund.portal.offer.OfferRepository.OfferRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/** Offers and marketing material: written by staff, shown to members (and public ones to visitors). Text and links only. */
@Service
public class OfferService implements OfferSource {

    public record OfferInput(String title, String body, String linkUrl, String imageUrl, String audience, LocalDate startsOn, LocalDate endsOn, Boolean active) {
    }

    public record AdminOffer(long id, String title, String body, String linkUrl, String imageUrl, String audience, LocalDate startsOn, LocalDate endsOn,
                             boolean active, String createdBy, String status) {
    }

    private final OfferRepository repo;
    private final Audit audit;

    public OfferService(OfferRepository repo, Audit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Override
    public List<OfferView> forMembers(LocalDate today) {
        return repo.live(today, false).stream().map(OfferService::view).toList();
    }

    public List<OfferView> forVisitors(LocalDate today) {
        return repo.live(today, true).stream().map(OfferService::view).toList();
    }

    public List<AdminOffer> adminList(LocalDate today) {
        return repo.all().stream().map(r -> admin(r, today)).toList();
    }

    @Transactional
    public AdminOffer create(OfferInput in, String actor, LocalDate today) {
        Clean c = clean(in);
        long id = repo.insert(c.title, c.body, c.link, c.image, c.audience, c.starts, c.ends, actor);
        audit.log("OFFER", String.valueOf(id), "OFFER_CREATED", actor, null, c.title);
        return admin(repo.byId(id).orElseThrow(), today);
    }

    @Transactional
    public AdminOffer update(long id, OfferInput in, String actor, LocalDate today) {
        OfferRow cur = repo.byId(id).orElseThrow(() -> PortalException.notFound("Offer", String.valueOf(id)));
        Clean c = clean(in);
        boolean active = in.active() == null ? cur.active() : in.active();
        repo.update(id, c.title, c.body, c.link, c.image, c.audience, c.starts, c.ends, active, actor);
        audit.log("OFFER", String.valueOf(id), "OFFER_UPDATED", actor, null, c.title);
        return admin(repo.byId(id).orElseThrow(), today);
    }

    @Transactional
    public AdminOffer setActive(long id, boolean active, String actor, LocalDate today) {
        repo.byId(id).orElseThrow(() -> PortalException.notFound("Offer", String.valueOf(id)));
        repo.setActive(id, active, actor);
        audit.log("OFFER", String.valueOf(id), active ? "OFFER_SHOWN" : "OFFER_HIDDEN", actor, null, null);
        return admin(repo.byId(id).orElseThrow(), today);
    }

    // ---------------------------------------------------------------- rules (package-visible for tests)

    record Clean(String title, String body, String link, String image, String audience, LocalDate starts, LocalDate ends) {
    }

    static Clean clean(OfferInput in) {
        if (in == null) {
            throw PortalException.badRequest("VALIDATION_FAILED", "The offer is required");
        }
        String title = in.title() == null ? "" : in.title().trim();
        if (title.isEmpty() || title.length() > 120) {
            throw PortalException.badRequest("VALIDATION_FAILED", "title is required, at most 120 characters");
        }
        String body = in.body() == null ? "" : in.body().trim();
        if (body.length() > 600) {
            throw PortalException.badRequest("VALIDATION_FAILED", "body is at most 600 characters");
        }
        String audience = in.audience() == null ? "MEMBERS" : in.audience().trim().toUpperCase();
        if (!audience.equals("PUBLIC") && !audience.equals("MEMBERS")) {
            throw PortalException.badRequest("VALIDATION_FAILED", "audience must be PUBLIC or MEMBERS");
        }
        if (in.startsOn() == null) {
            throw PortalException.badRequest("VALIDATION_FAILED", "startsOn is required");
        }
        if (in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) {
            throw PortalException.badRequest("VALIDATION_FAILED", "endsOn cannot be before startsOn");
        }
        return new Clean(title, body, url(in.linkUrl(), "linkUrl"), url(in.imageUrl(), "imageUrl"), audience, in.startsOn(), in.endsOn());
    }

    /** Only http(s) links: a javascript: or data: link in an offer would run in the member's browser. */
    static String url(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.trim();
        boolean ok = u.length() <= 500;
        if (ok) {
            try {
                URI uri = new URI(u);
                ok = uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
            } catch (Exception e) {
                ok = false;
            }
        }
        if (!ok) {
            throw PortalException.badRequest("VALIDATION_FAILED", field + " must be a web address starting with http:// or https:// (at most 500 characters)");
        }
        return u;
    }

    static String status(OfferRow r, LocalDate today) {
        if (!r.active()) {
            return "HIDDEN";
        }
        if (r.startsOn().isAfter(today)) {
            return "SCHEDULED";
        }
        return r.endsOn() != null && r.endsOn().isBefore(today) ? "ENDED" : "LIVE";
    }

    private static OfferView view(OfferRow r) {
        return new OfferView(r.id(), r.title(), r.body(), r.linkUrl(), r.imageUrl(), r.audience(), r.startsOn(), r.endsOn());
    }

    private static AdminOffer admin(OfferRow r, LocalDate today) {
        return new AdminOffer(r.id(), r.title(), r.body(), r.linkUrl(), r.imageUrl(), r.audience(), r.startsOn(), r.endsOn(), r.active(), r.createdBy(), status(r, today));
    }
}
