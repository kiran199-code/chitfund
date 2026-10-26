package com.chitfund.service.maintenance;

import java.math.BigDecimal;
import java.util.List;

/**
 * The fixed sample set for the customer portal's plan shelf: two groups for each of the six plan types (12 in all). Values are sample
 * placeholders for testing the screens, not real products; branch names are examples. Every chit value divides evenly by its tickets.
 */
public final class SamplePlans {

    public record Plan(String category, String title, String branch, String state, BigDecimal chitValue, int tickets) {
    }

    public static final List<String> CATEGORIES = List.of("EDUCATION", "HOME", "WEDDING", "SHORT_TERM", "RETIREMENT", "BUSINESS");

    public static final List<Plan> ALL = List.of(
            new Plan("EDUCATION", "Education Plan", "Siddipet", "Telangana", new BigDecimal("1000000"), 40),
            new Plan("EDUCATION", "Education Plan", "Kukatpally", "Telangana", new BigDecimal("500000"), 25),
            new Plan("HOME", "Home Plan", "Rotary Nagar", "Telangana", new BigDecimal("500000"), 40),
            new Plan("HOME", "Home Plan", "Secunderabad", "Telangana", new BigDecimal("1000000"), 50),
            new Plan("WEDDING", "Wedding Plan", "Kukatpally", "Telangana", new BigDecimal("300000"), 20),
            new Plan("WEDDING", "Wedding Plan", "Warangal", "Telangana", new BigDecimal("200000"), 25),
            new Plan("SHORT_TERM", "Short term Plan", "Secunderabad", "Telangana", new BigDecimal("100000"), 10),
            new Plan("SHORT_TERM", "Short term Plan", "Ameerpet", "Telangana", new BigDecimal("200000"), 20),
            new Plan("RETIREMENT", "Retirement Plan", "Banjara Hills", "Telangana", new BigDecimal("2000000"), 50),
            new Plan("RETIREMENT", "Retirement Plan", "Karimnagar", "Telangana", new BigDecimal("500000"), 50),
            new Plan("BUSINESS", "Business Plan", "Banjara Hills", "Telangana", new BigDecimal("2000000"), 40),
            new Plan("BUSINESS", "Business Plan", "Nizamabad", "Telangana", new BigDecimal("1000000"), 25));

    private SamplePlans() {
    }

    /** "Education Plan 10 lakh, Siddipet" */
    public static String name(Plan p) {
        BigDecimal lakh = p.chitValue().divide(new BigDecimal("100000"));
        String l = lakh.stripTrailingZeros().toPlainString();
        return p.title() + " " + l + " lakh, " + p.branch();
    }
}
