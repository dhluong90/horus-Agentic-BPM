package io.github.dhluong90.bpm.process.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** A clock that moves only when a test moves it. Thread-safe. */
public final class TestClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    private TestClock(Instant now, ZoneId zone) {
        this.now = Objects.requireNonNull(now, "now must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * @param start the initial instant
     * @return a UTC test clock
     */
    public static TestClock at(Instant start) {
        return new TestClock(start, ZoneOffset.UTC);
    }

    /**
     * @param iso the initial instant in ISO 8601, for example {@code 2027-01-04T09:00:00Z}
     * @return a UTC test clock
     */
    public static TestClock at(String iso) {
        return at(Instant.parse(iso));
    }

    /**
     * @param duration how far to move
     * @return this clock
     */
    public TestClock advance(Duration duration) {
        now = now.plus(duration);
        return this;
    }

    /**
     * @param instant the new time
     * @return this clock
     */
    public TestClock set(Instant instant) {
        now = Objects.requireNonNull(instant, "instant must not be null");
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
