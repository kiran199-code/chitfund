package com.chitfund.service.listing;

import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.listing.ListingRepository.OpenPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class ListingService {

    public static final Set<String> CATEGORIES = Set.of("EDUCATION", "HOME", "WEDDING", "SHORT_TERM", "RETIREMENT", "BUSINESS");

    public record ListingRequest(String planCategory, String branch, String state) {
    }

    public record ListingView(String groupCode, String planCategory, String branch, String state) {
    }

    private final ListingRepository repo;
    private final Audit audit;

    public ListingService(ListingRepository repo, Audit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OpenPlan> openPlans() {
        return repo.openPlans();
    }

    /** Blank values clear the label. Category must be one of the six plan types. */
    @Transactional
    public ListingView set(String groupCode, ListingRequest req, String actor) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        String category = blankToNull(req.planCategory());
        if (category != null) {
            category = category.trim().toUpperCase();
            if (!CATEGORIES.contains(category)) {
                throw ChitException.badRequest("VALIDATION_FAILED", "planCategory must be one of " + String.join(", ", CATEGORIES.stream().sorted().toList()));
            }
        }
        String branch = limit("branch", req.branch(), 60);
        String state = limit("state", req.state(), 40);
        if (repo.setListing(groupCode, category, branch, state, actor) == 0) {
            throw ChitException.notFound("Group", groupCode);
        }
        audit.log("GROUP", groupCode, "LISTING_SET", actor, (category == null ? "-" : category) + " / " + (branch == null ? "-" : branch) + " / " + (state == null ? "-" : state));
        return new ListingView(groupCode, category, branch, state);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String limit(String field, String value, int max) {
        String v = blankToNull(value);
        if (v != null && v.length() > max) {
            throw ChitException.badRequest("VALIDATION_FAILED", field + " must be at most " + max + " characters");
        }
        return v;
    }
}
