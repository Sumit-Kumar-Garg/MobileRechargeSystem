package com.app.recharge.payment.spi;

public record PspChargeRequest(String merchantRef, long amountMinor, String currency, String paymentToken) { }
