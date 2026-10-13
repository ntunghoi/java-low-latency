package com.ntunghoi.trading.model;

public record Order(
        String orderId,
        String instrument,
        Side side,
        OrderType type,
        long priceInCents,
        long quantity,
        OrderStatus status,
        long sequenceNumber,
        long timestampNanos
) {
    public boolean isMarket() {
        return type == OrderType.MARKET;
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(
                orderId,
                instrument,
                side,
                type,
                priceInCents,
                quantity,
                newStatus,
                sequenceNumber,
                timestampNanos
        );
    }

    public Order withQuantity(long newQuantity) {
        return new Order(
                orderId,
                instrument,
                side,
                type,
                priceInCents,
                newQuantity,
                status,
                sequenceNumber,
                timestampNanos
        );
    }

    public boolean isBuy() {
        return side == Side.BUY;
    }
}
