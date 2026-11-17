package com.app.recharge.number;

import java.util.UUID;

public record NumberView(UUID id, String msisdnMasked, String market) {

    /** Builds the API view from the entity (masked number only). */
    static NumberView from(SubscriberNumber n) {
        return new NumberView(n.getId(), n.getMsisdnMasked(), n.getMarket());
    }
}
