package com.chitfund.portal.mail;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts in the Indian digit grouping (1,00,000.00), for emails. */
public final class Inr {

    private Inr() {
    }

    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        String sign = a.signum() < 0 ? "-" : "";
        String[] parts = a.abs().toPlainString().split("\\.");
        String whole = parts[0];
        String grouped;
        if (whole.length() <= 3) {
            grouped = whole;
        } else {
            String last3 = whole.substring(whole.length() - 3);
            String rest = whole.substring(0, whole.length() - 3);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                if (i > 0 && (rest.length() - i) % 2 == 0) {
                    sb.append(',');
                }
                sb.append(rest.charAt(i));
            }
            grouped = sb + "," + last3;
        }
        return "Rs. " + sign + grouped + "." + parts[1];
    }
}
