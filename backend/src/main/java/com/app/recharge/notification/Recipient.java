package com.app.recharge.notification;

import java.util.List;
import java.util.UUID;

/** Contact data resolved at send time (never stored in Kafka). */
public record Recipient(UUID userId, String msisdn, String email, List<String> pushTokens) { }
