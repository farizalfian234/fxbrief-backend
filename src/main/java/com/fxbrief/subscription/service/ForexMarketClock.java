package com.fxbrief.subscription.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Forex market is closed from Friday 22:00 UTC to Sunday 22:00 UTC.
 * Forex day boundary is 22:00 UTC daily (New York close / Sydney open).
 * Same for every user regardless of timezone (PRD §5.3).
 */
@Component
public class ForexMarketClock {

    static final int DAY_BOUNDARY_HOUR_UTC = 22;

    private final Clock clock;

    public ForexMarketClock() {
        this(Clock.systemUTC());
    }

    ForexMarketClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isMarketOpen() {
        return isMarketOpenAt(Instant.now(clock));
    }

    boolean isMarketOpenAt(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        int hour = utc.getHour();

        if (day == DayOfWeek.SATURDAY) {
            return false;
        }
        if (day == DayOfWeek.FRIDAY && hour >= DAY_BOUNDARY_HOUR_UTC) {
            return false;
        }
        if (day == DayOfWeek.SUNDAY && hour < DAY_BOUNDARY_HOUR_UTC) {
            return false;
        }
        return true;
    }

    /**
     * The current forex market date. The boundary is 22:00 UTC: any instant from
     * 22:00 UTC on day D up to (but not including) 22:00 UTC on day D+1 belongs to
     * forex market date D+1. This mirrors the New York close / Sydney open semantics
     * referenced in PRD §5.3 and is the date stamped on every subscription_usage row.
     */
    public LocalDate currentForexMarketDate() {
        return forexMarketDateAt(Instant.now(clock));
    }

    LocalDate forexMarketDateAt(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        if (utc.getHour() >= DAY_BOUNDARY_HOUR_UTC) {
            return utc.toLocalDate().plusDays(1);
        }
        return utc.toLocalDate();
    }
}
