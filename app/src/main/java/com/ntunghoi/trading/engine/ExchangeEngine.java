package com.ntunghoi.trading.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ExchangeEngine {
    private final ConcurrentHashMap<String, OrderBook> books =
            new ConcurrentHashMap<>(32);
}
