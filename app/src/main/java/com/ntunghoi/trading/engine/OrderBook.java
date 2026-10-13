package com.ntunghoi.trading.engine;

import com.ntunghoi.trading.model.Order;
import com.ntunghoi.trading.model.OrderStatus;
import com.ntunghoi.trading.model.OrderType;
import com.ntunghoi.trading.model.Trade;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.StampedLock;

@Slf4j
public class OrderBook {
    private final String instrument;

    /**
     * Bids: price -> FIFO queue of buy orders
     * Sorted ASCENDING so first() = best (highest) bid
     */
    private final ConcurrentSkipListMap<Long, ArrayDeque<Order>> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /**
     * Ask: price -> FIFO queue of sell orders
     * Sorted ASCENDED so first() = best (lowest) ask
     */
    private final ConcurrentSkipListMap<Long, ArrayDeque<Order>> asks =
            new ConcurrentSkipListMap<>();

    private final ConcurrentHashMap<String, Order> activeOrders =
            new ConcurrentHashMap<>(1024, 0.75f, 16);

    public OrderBook(String instrument) {
        this.instrument = instrument;
    }

    /**
     * StampLock: allow optimistic reads (zero-cost when no writer)
     * and exclusive writes during matching
     * WHY NOT synchronized?
     * - synchronized holds a monitor lock even for reds
     * - StampedLock optimistic read = just a counter check (no CAS)
     * - Under low write contention, reads are nearly free
     */
    private final StampedLock lock = new StampedLock();

    public List<Trade> submit(Order order) {
        if (order.quantity() <= 0) {
            log.warn("Rejected order {} - non-positive quantity", order.orderId());
            return List.of();
        }
        if (order.type() == OrderType.LIMIT && order.priceInCents() <= 0) {
            log.warn("Rejected order {} - non-positive price", order.orderId());
            return List.of();
        }

        long stamp = lock.writeLock();
        try {
            return match(order);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Price-time Priority Matching Algorithm
     * For a BUY order: match against asks ascending (lowest ask first)
     * while ask.price <= buy.price (for market order)
     * For a SELL order: match against bids descending (highest bid first)
     * while bid.price >= sell.price (or market order)
     * Each match reduces both orders' remaining quantities.
     * Fully filled orders are removed. Partial filled remain in the book.
     */
    private List<Trade> match(Order incoming) {
        List<Trade> trades = new ArrayList<>(4); // pre-size, most orders match 0-4 counterparts
        Order remaining = incoming;

        var oppositeBook = remaining.isBuy() ? asks : bids;

        while (remaining.quantity() > 0 && !oppositeBook.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> bestEntry = oppositeBook.firstEntry();
            if (bestEntry == null) {
                break;
            }

            long bestPrice = bestEntry.getKey();

            // Price crossing check (skip for market orders)
            if (!remaining.isMarket()) {
                if (remaining.isBuy() && bestPrice > remaining.priceInCents()) {
                    break;
                }
                if (!remaining.isBuy() && bestPrice < remaining.priceInCents()) {
                    break;
                }
            }

            ArrayDeque<Order> queue = bestEntry.getValue();
            while (!queue.isEmpty() && remaining.quantity() > 0) {
                Order resting = queue.peek(); // do not remove yet

                long matchedQuantity = Math.min(remaining.quantity(), resting.quantity());
                long executionPrice = resting.priceInCents(); // resting order sets price

                String buyId = remaining.isBuy() ? remaining.orderId() : resting.orderId();
                String sellId = remaining.isBuy() ? resting.orderId() : remaining.orderId();

                Trade trade = new Trade(
                        java.util.UUID.randomUUID().toString(),
                        instrument,
                        buyId,
                        sellId,
                        executionPrice,
                        matchedQuantity,
                        System.nanoTime()
                );
                trades.add(trade);

                log.debug("Trade: {} {} @ {} cents, quantity: {}", instrument, executionPrice, matchedQuantity, trade.tradeId());

                long restingRemaining = resting.quantity() - matchedQuantity;
                if (restingRemaining == 0) {
                    queue.poll(); // remove as fully filled
                    activeOrders.remove(resting.orderId());
                } else {
                    // Partial filled: replace head with reduced-quantity order
                    queue.poll();
                    Order partialResting = resting.withQuantity(restingRemaining)
                            .withStatus(OrderStatus.PARTIAL);
                    queue.addFirst(partialResting);
                    activeOrders.put(partialResting.orderId(), partialResting);
                }

                remaining = remaining.withQuantity(remaining.quantity() - matchedQuantity);
            }

            if (queue.isEmpty()) {
                oppositeBook.remove(bestPrice);
            }
        }

        if (remaining.quantity() > 0 && !remaining.isMarket()) {
            OrderStatus status = trades.isEmpty() ? OrderStatus.NEW : OrderStatus.PARTIAL;
            Order toRest = remaining.withStatus(status);
            addToBook(toRest);
        }

        return trades;
    }

    /**
     * Add a resting order has remaining quantity AND is a limit order -> add to book
     */
    private void addToBook(Order order) {
        var book = order.isBuy() ? bids : asks;
        // computeIfAbsent: atomic creates queue only if price level is new
        book.computeIfAbsent(order.priceInCents(), k -> new ArrayDeque<>(8))
                .addLast(order);
        activeOrders.put(order.orderId(), order);
        log.debug("Resting {} {} {} @ {} cents, quantity {}",
                order.side(),
                order.orderId(),
                instrument,
                order.priceInCents(),
                order.quantity()
        );
    }
}
