package com.app.recharge.provisioning.spi;

public record OcsRequest(String referenceId, String msisdn, String planCode, int validityDays) { }
