package com.app.recharge.recommendation;

import java.util.UUID;

public record RecommendationView(
        UUID planId,
        String planCode,
        String reason,
        boolean personalized,
        UUID cheaperAlternativePlanId,
        String modelVersion,
        String explanation) { }
