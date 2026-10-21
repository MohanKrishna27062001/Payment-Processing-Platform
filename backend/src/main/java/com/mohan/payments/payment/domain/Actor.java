package com.mohan.payments.payment.domain;

import java.util.UUID;

/** Who caused a state change: a merchant (through the API) or a system component (processor, worker). */
public record Actor(ActorType type, String id) {

    public enum ActorType {
        MERCHANT,
        SYSTEM
    }

    public static Actor merchant(UUID merchantId) {
        return new Actor(ActorType.MERCHANT, merchantId.toString());
    }

    public static Actor system(String component) {
        return new Actor(ActorType.SYSTEM, component);
    }
}
