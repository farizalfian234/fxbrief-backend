package com.fxbrief.analysis.entity;

import java.util.Arrays;
import java.util.Optional;

public enum Pair {

    EUR_USD("EUR/USD"),
    GBP_USD("GBP/USD"),
    USD_JPY("USD/JPY"),
    USD_CHF("USD/CHF"),
    AUD_USD("AUD/USD"),
    USD_CAD("USD/CAD"),
    NZD_USD("NZD/USD"),
    XAU_USD("XAU/USD");

    private final String symbol;

    Pair(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getBaseCurrency() {
        return symbol.substring(0, 3);
    }

    public String getQuoteCurrency() {
        return symbol.substring(4);
    }

    public static Optional<Pair> fromSymbol(String symbol) {
        return Arrays.stream(values())
                .filter(p -> p.symbol.equals(symbol))
                .findFirst();
    }
}
