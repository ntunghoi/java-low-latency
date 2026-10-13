package com.ntunghoi.trading.model;

public record Trade(
        String tradeId,
        String instrument,
        String buyOrderId,
        String sellOrderId,
        long priceInCents,
        long quantity,
        long executionNanos
) {
}
