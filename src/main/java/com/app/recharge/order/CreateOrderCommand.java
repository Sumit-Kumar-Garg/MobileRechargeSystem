package com.app.recharge.order;

import java.util.UUID;

public record CreateOrderCommand(UUID numberId, UUID planId, String idempotencyKey, UUID journeyId) { }
