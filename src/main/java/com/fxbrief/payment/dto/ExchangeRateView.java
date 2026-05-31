package com.fxbrief.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateView(
        BigDecimal usdToIdr,
        Instant fetchedAt
) {}
