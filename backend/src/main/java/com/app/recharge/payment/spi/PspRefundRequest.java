package com.app.recharge.payment.spi;

public record PspRefundRequest(String merchantRef, String originalMerchantRef, long amountMinor, String currency) { }
