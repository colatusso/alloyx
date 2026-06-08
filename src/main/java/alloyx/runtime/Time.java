package alloyx.runtime;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Faithful runtime implementation of the Apex {@code System.Time} type.
 *
 * <p>Apex {@code Time} represents a time of day with millisecond precision and
 * no date or time zone. It is backed here by {@link java.time.LocalTime}.</p>
 *
 * <p>Reference:
 * <a href="https://developer.salesforce.com/docs/atlas.en-us.apexref.meta/apexref/apex_methods_system_time.htm">
 * Apex Time class</a>.</p>
 */
public final class Time {

    /** Underlying immutable time value. Never {@code null}. */
    private final LocalTime value;

    private Time(LocalTime value) {
        this.value = value;
    }

    /** Wraps an existing {@link LocalTime}. Package use. */
    static Time of(LocalTime value) {
        return new Time(value);
    }

    /** Exposes the underlying {@link LocalTime}. Package use. */
    LocalTime toLocalTime() {
        return value;
    }

    // ------------------------------------------------------------------
    // Static constructors
    // ------------------------------------------------------------------

    /**
     * Constructs a Time from its components. The millisecond argument is
     * converted to nanoseconds internally.
     */
    public static Time newInstance(int hour, int minute, int second, int millisecond) {
        return new Time(LocalTime.of(hour, minute, second, millisecond * 1_000_000));
    }

    // ------------------------------------------------------------------
    // Component accessors
    // ------------------------------------------------------------------

    /** Returns the hour component (0-23). */
    public int hour() {
        return value.getHour();
    }

    /** Returns the minute component (0-59). */
    public int minute() {
        return value.getMinute();
    }

    /** Returns the second component (0-59). */
    public int second() {
        return value.getSecond();
    }

    /** Returns the millisecond component (0-999). */
    public int millisecond() {
        return value.getNano() / 1_000_000;
    }

    // ------------------------------------------------------------------
    // Arithmetic (return a new Time; Time is immutable)
    // ------------------------------------------------------------------

    /** Adds the given number of hours (may be negative), wrapping around midnight. */
    public Time addHours(int hours) {
        return new Time(value.plusHours(hours));
    }

    /** Adds the given number of minutes (may be negative), wrapping around midnight. */
    public Time addMinutes(int minutes) {
        return new Time(value.plusMinutes(minutes));
    }

    /** Adds the given number of seconds (may be negative), wrapping around midnight. */
    public Time addSeconds(int seconds) {
        return new Time(value.plusSeconds(seconds));
    }

    /** Adds the given number of milliseconds (may be negative), wrapping around midnight. */
    public Time addMilliseconds(int milliseconds) {
        return new Time(value.plusNanos((long) milliseconds * 1_000_000));
    }

    // ------------------------------------------------------------------
    // Equality / ordering
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Time)) {
            return false;
        }
        return value.equals(((Time) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Apex renders a Time as {@code HH:mm:ss.SSS}. */
    @Override
    public String toString() {
        return value.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
}
