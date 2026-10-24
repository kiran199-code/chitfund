package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.BidView;
import com.chitfund.service.auction.AuctionDtos.DrawView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the screens receive: a draw seed is a 64-bit number, which a browser cannot hold exactly, so it travels as text. */
class AuctionDtosJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void aDrawSeedIsSentAsTextSoNoDigitIsLost() throws Exception {
        long seed = 9_007_199_254_740_993L; // 2^53 + 1: not representable as a JavaScript number
        DrawView draw = new DrawView(1, "TIE_BREAK", "SHA256-MOD-V1", seed, List.of(2, 3), List.of(3), OffsetDateTime.parse("2026-11-10T10:00:00Z"), "ops");
        JsonNode n = JSON.readTree(JSON.writeValueAsString(draw));
        assertTrue(n.get("seed").isTextual());
        assertEquals("9007199254740993", n.get("seed").asText());
        assertEquals(2, n.get("candidates").get(0).asInt());
    }

    @Test
    void aHiddenSealedBidLeavesTheAmountOutOfTheJson() throws Exception {
        BidView sealed = new BidView(1, 4, null, OffsetDateTime.parse("2026-11-10T10:00:00Z"), "ops", "VALID", null, false, false);
        JsonNode n = JSON.readTree(JSON.writeValueAsString(sealed));
        assertFalse(n.has("discountAmount"));
        BidView open = new BidView(2, 5, new BigDecimal("12000.00"), OffsetDateTime.parse("2026-11-10T10:01:00Z"), "ops", "VALID", null, false, false);
        assertTrue(JSON.readTree(JSON.writeValueAsString(open)).has("discountAmount"));
    }
}
