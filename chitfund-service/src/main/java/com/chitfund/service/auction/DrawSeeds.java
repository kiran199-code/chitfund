package com.chitfund.service.auction;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Where the seed of a draw comes from. The seed is chosen when the draw is made and stored with the draw record, so the
 * draw can be replayed (see {@code calc/Draw}); tests substitute a fixed seed.
 */
@Component
public class DrawSeeds {

    private final SecureRandom random = new SecureRandom();

    public long next() {
        return random.nextLong();
    }
}
