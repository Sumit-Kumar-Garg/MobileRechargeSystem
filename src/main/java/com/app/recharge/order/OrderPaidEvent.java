package com.app.recharge.order;

import java.util.UUID;

/** In-process event published when payment commits; triggers provisioning after the transaction is durable. */
public record OrderPaidEvent(UUID orderId) { }
