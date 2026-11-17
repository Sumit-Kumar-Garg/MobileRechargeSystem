package com.app.recharge.ratelimit;

public record RateLimitResult(boolean allowed, long remaining, long retryAfterSeconds) { }
