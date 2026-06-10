// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Faithful runtime implementation of the Apex {@code System.Datetime} type.
 *
 * <p>An Apex {@code Datetime} is an absolute instant in time (epoch millis),
 * not a wall-clock reading. It is backed here by a single {@link Instant} —
 * the one source of truth. Components and formatting are rendered against a
 * time zone on demand: the "local" methods use the running user's zone
 * (locally, the JVM default, the analog of the org user's locale zone) and the
 * {@code *Gmt} variants use UTC. {@link #getTime()} is therefore
 * machine-independent.</p>
 *
 * <p>Calendar-unit arithmetic ({@code addDays}/{@code addMonths}/{@code addYears})
 * is performed in the GMT calendar of the instant, matching Apex.</p>
 *
 * <p>Reference:
 * <a href="https://developer.salesforce.com/docs/atlas.en-us.apexref.meta/apexref/apex_methods_system_datetime.htm">
 * Apex Datetime class</a>.</p>
 */
public final class Datetime {

    /** The local zone analog: the JVM default stands in for the org user's locale zone. */
    private static ZoneId localZone() {
        return ZoneId.systemDefault();
    }

    /** The single source of truth: an absolute point in time. Never {@code null}. */
    private final Instant instant;

    private Datetime(Instant instant) {
        this.instant = instant;
    }

    /** Wraps an existing {@link Instant}. Package use. */
    static Datetime ofInstant(Instant instant) {
        return new Datetime(instant);
    }

    /** Exposes the underlying {@link Instant}. Package use. */
    Instant toInstant() {
        return instant;
    }

    // ------------------------------------------------------------------
    // Static constructors
    // ------------------------------------------------------------------

    /** Returns the current instant. */
    public static Datetime now() {
        return new Datetime(Instant.now());
    }

    /**
     * Constructs a Datetime from the number of milliseconds since the Unix epoch
     * (1970-01-01T00:00:00 UTC).
     */
    public static Datetime newInstance(long millis) {
        return new Datetime(Instant.ofEpochMilli(millis));
    }

    /** Constructs a Datetime from components interpreted in the running user's local zone. */
    public static Datetime newInstance(int year, int month, int day,
                                       int hour, int minute, int second) {
        return fromComponents(year, month, day, hour, minute, second, localZone());
    }

    /** Constructs a Datetime from components interpreted as GMT. */
    public static Datetime newInstanceGmt(int year, int month, int day,
                                          int hour, int minute, int second) {
        return fromComponents(year, month, day, hour, minute, second, ZoneOffset.UTC);
    }

    private static Datetime fromComponents(int year, int month, int day,
                                           int hour, int minute, int second, ZoneId zone) {
        return new Datetime(LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(zone).toInstant());
    }

    /**
     * Parses a String into a Datetime, interpreting the wall-clock value in the
     * running user's local zone.
     *
     * <p>Apex's local {@code valueOf} accepts {@code "yyyy-MM-dd HH:mm:ss"}; the
     * time portion is optional, in which case midnight is assumed.</p>
     */
    public static Datetime valueOf(String s) {
        return new Datetime(parseLocal(s).atZone(localZone()).toInstant());
    }

    /** Like {@link #valueOf(String)} but interprets the wall-clock value as GMT. */
    public static Datetime valueOfGmt(String s) {
        return new Datetime(parseLocal(s).atZone(ZoneOffset.UTC).toInstant());
    }

    private static LocalDateTime parseLocal(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Datetime.valueOf: argument cannot be null");
        }
        String trimmed = s.trim().replace('T', ' ');
        int sep = trimmed.indexOf(' ');
        if (sep < 0) {
            return Date.valueOf(trimmed).toLocalDate().atStartOfDay();
        }
        String datePart = trimmed.substring(0, sep);
        String timePart = trimmed.substring(sep + 1).trim();
        // Normalize a missing seconds component (HH:mm) to HH:mm:ss.
        if (timePart.chars().filter(c -> c == ':').count() == 1) {
            timePart = timePart + ":00";
        }
        return LocalDateTime.parse(datePart + "T" + timePart,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    private ZonedDateTime atLocal() {
        return instant.atZone(localZone());
    }

    private ZonedDateTime atGmt() {
        return instant.atZone(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    // Date / Time extraction
    // ------------------------------------------------------------------

    /** Returns the {@link Date} portion in the user's local zone. */
    public Date date() {
        return Date.of(atLocal().toLocalDate());
    }

    /** Returns the {@link Time} portion in the user's local zone. */
    public Time time() {
        return Time.of(atLocal().toLocalTime());
    }

    /** Returns the {@link Date} portion in GMT. */
    public Date dateGmt() {
        return Date.of(atGmt().toLocalDate());
    }

    /** Returns the {@link Time} portion in GMT. */
    public Time timeGmt() {
        return Time.of(atGmt().toLocalTime());
    }

    // ------------------------------------------------------------------
    // Arithmetic (return a new Datetime; Datetime is immutable)
    // ------------------------------------------------------------------
    // Apex performs calendar-unit arithmetic in the GMT calendar of the instant;
    // hour/minute/second arithmetic is a pure offset on the instant.

    /** Adds the given number of days in the GMT calendar (may be negative). */
    public Datetime addDays(int additionalDays) {
        return new Datetime(atGmt().plusDays(additionalDays).toInstant());
    }

    /** Adds the given number of months in the GMT calendar (may be negative). */
    public Datetime addMonths(int additionalMonths) {
        return new Datetime(atGmt().plusMonths(additionalMonths).toInstant());
    }

    /** Adds the given number of years in the GMT calendar (may be negative). */
    public Datetime addYears(int additionalYears) {
        return new Datetime(atGmt().plusYears(additionalYears).toInstant());
    }

    /** Adds the given number of hours to the instant (may be negative). */
    public Datetime addHours(int additionalHours) {
        return new Datetime(instant.plusSeconds((long) additionalHours * 3600));
    }

    /** Adds the given number of minutes to the instant (may be negative). */
    public Datetime addMinutes(int additionalMinutes) {
        return new Datetime(instant.plusSeconds((long) additionalMinutes * 60));
    }

    /** Adds the given number of seconds to the instant (may be negative). */
    public Datetime addSeconds(int additionalSeconds) {
        return new Datetime(instant.plusSeconds(additionalSeconds));
    }

    // ------------------------------------------------------------------
    // Epoch
    // ------------------------------------------------------------------

    /**
     * Returns the number of milliseconds since the Unix epoch
     * (1970-01-01T00:00:00 UTC). Machine-independent.
     */
    public long getTime() {
        return instant.toEpochMilli();
    }

    // ------------------------------------------------------------------
    // Component accessors (local zone)
    // ------------------------------------------------------------------

    /** Returns the year component in the user's local zone. */
    public int year() {
        return atLocal().getYear();
    }

    /** Returns the month component (1-12) in the user's local zone. */
    public int month() {
        return atLocal().getMonthValue();
    }

    /** Returns the day-of-month component (1-31) in the user's local zone. */
    public int day() {
        return atLocal().getDayOfMonth();
    }

    /** Returns the hour component (0-23) in the user's local zone. */
    public int hour() {
        return atLocal().getHour();
    }

    /** Returns the minute component (0-59) in the user's local zone. */
    public int minute() {
        return atLocal().getMinute();
    }

    /** Returns the second component (0-59) in the user's local zone. */
    public int second() {
        return atLocal().getSecond();
    }

    /** Returns the millisecond component (0-999). Zone-independent. */
    public int millisecond() {
        return instant.getNano() / 1_000_000;
    }

    // ------------------------------------------------------------------
    // Component accessors (GMT)
    // ------------------------------------------------------------------

    /** Returns the year component in GMT. */
    public int yearGmt() {
        return atGmt().getYear();
    }

    /** Returns the month component (1-12) in GMT. */
    public int monthGmt() {
        return atGmt().getMonthValue();
    }

    /** Returns the day-of-month component (1-31) in GMT. */
    public int dayGmt() {
        return atGmt().getDayOfMonth();
    }

    /** Returns the hour component (0-23) in GMT. */
    public int hourGmt() {
        return atGmt().getHour();
    }

    /** Returns the minute component (0-59) in GMT. */
    public int minuteGmt() {
        return atGmt().getMinute();
    }

    /** Returns the second component (0-59) in GMT. */
    public int secondGmt() {
        return atGmt().getSecond();
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * Returns the Datetime as a String using the locale and default format of
     * the context user, in the local zone. Approximated with a medium localized style.
     */
    public String format() {
        return atLocal().format(
                DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                        .withLocale(Locale.getDefault()));
    }

    /**
     * Returns the Datetime as a String using the supplied Java
     * {@link DateTimeFormatter} pattern, rendered in the local zone.
     */
    public String format(String fmt) {
        return atLocal().format(DateTimeFormatter.ofPattern(fmt));
    }

    /**
     * Same as {@link #format(String)} but rendered in the given time zone
     * (e.g. {@code "GMT"}, {@code "America/Sao_Paulo"}).
     */
    public String format(String fmt, String timezone) {
        return instant.atZone(ZoneId.of(timezone))
                .format(DateTimeFormatter.ofPattern(fmt));
    }

    /** Same as {@link #format(String)} but rendered in GMT. */
    public String formatGmt(String fmt) {
        return atGmt().format(DateTimeFormatter.ofPattern(fmt));
    }

    // ------------------------------------------------------------------
    // Equality / ordering (compare instants)
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Datetime)) {
            return false;
        }
        return instant.equals(((Datetime) o).instant);
    }

    @Override
    public int hashCode() {
        return instant.hashCode();
    }

    /** Apex renders a Datetime as {@code yyyy-MM-dd HH:mm:ss} in the local zone. */
    @Override
    public String toString() {
        return atLocal().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
