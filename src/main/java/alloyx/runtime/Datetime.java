package alloyx.runtime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Faithful runtime implementation of the Apex {@code System.Datetime} type.
 *
 * <p>Apex {@code Datetime} represents a point in time to the millisecond. It is
 * backed here by {@link java.time.LocalDateTime}, interpreted in the system
 * default time zone for epoch conversions. This keeps the model simple while
 * supporting the common methods used by transpiled code.</p>
 *
 * <p>Reference:
 * <a href="https://developer.salesforce.com/docs/atlas.en-us.apexref.meta/apexref/apex_methods_system_datetime.htm">
 * Apex Datetime class</a>.</p>
 */
public final class Datetime {

    /** Underlying immutable date-time value. Never {@code null}. */
    private final LocalDateTime value;

    private Datetime(LocalDateTime value) {
        this.value = value;
    }

    /** Wraps an existing {@link LocalDateTime}. Package use. */
    static Datetime of(LocalDateTime value) {
        return new Datetime(value);
    }

    /** Exposes the underlying {@link LocalDateTime}. Package use. */
    LocalDateTime toLocalDateTime() {
        return value;
    }

    // ------------------------------------------------------------------
    // Static constructors
    // ------------------------------------------------------------------

    /** Returns the current date and time. */
    public static Datetime now() {
        return new Datetime(LocalDateTime.now());
    }

    /**
     * Constructs a Datetime from the number of milliseconds since the Unix epoch
     * (1970-01-01T00:00:00 UTC), interpreted in the system default time zone.
     */
    public static Datetime newInstance(long millis) {
        return new Datetime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    /** Constructs a Datetime from year, month, day, hour, minute and second components. */
    public static Datetime newInstance(int year, int month, int day,
                                       int hour, int minute, int second) {
        return new Datetime(LocalDateTime.of(year, month, day, hour, minute, second));
    }

    /**
     * Parses a String into a Datetime.
     *
     * <p>Apex's local {@code valueOf} accepts the format
     * {@code "yyyy-MM-dd HH:mm:ss"}; the time portion is optional, in which case
     * midnight is assumed.</p>
     */
    public static Datetime valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Datetime.valueOf: argument cannot be null");
        }
        String trimmed = s.trim().replace('T', ' ');
        int sep = trimmed.indexOf(' ');
        if (sep < 0) {
            // Date only -> midnight.
            return new Datetime(Date.valueOf(trimmed).toLocalDate().atStartOfDay());
        }
        String datePart = trimmed.substring(0, sep);
        String timePart = trimmed.substring(sep + 1).trim();
        // Normalize a missing seconds component (HH:mm) to HH:mm:ss.
        if (timePart.chars().filter(c -> c == ':').count() == 1) {
            timePart = timePart + ":00";
        }
        LocalDateTime parsed = LocalDateTime.parse(
                datePart + "T" + timePart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return new Datetime(parsed);
    }

    // ------------------------------------------------------------------
    // Date / Time extraction
    // ------------------------------------------------------------------

    /** Returns the {@link Date} portion (user-local). */
    public Date date() {
        return Date.of(value.toLocalDate());
    }

    /** Returns the {@link Time} portion (user-local). */
    public Time time() {
        return Time.of(value.toLocalTime());
    }

    // ------------------------------------------------------------------
    // Arithmetic (return a new Datetime; Datetime is immutable)
    // ------------------------------------------------------------------

    /** Adds the given number of days (may be negative). */
    public Datetime addDays(int additionalDays) {
        return new Datetime(value.plusDays(additionalDays));
    }

    /** Adds the given number of months (may be negative). */
    public Datetime addMonths(int additionalMonths) {
        return new Datetime(value.plusMonths(additionalMonths));
    }

    /** Adds the given number of years (may be negative). */
    public Datetime addYears(int additionalYears) {
        return new Datetime(value.plusYears(additionalYears));
    }

    /** Adds the given number of hours (may be negative). */
    public Datetime addHours(int additionalHours) {
        return new Datetime(value.plusHours(additionalHours));
    }

    /** Adds the given number of minutes (may be negative). */
    public Datetime addMinutes(int additionalMinutes) {
        return new Datetime(value.plusMinutes(additionalMinutes));
    }

    /** Adds the given number of seconds (may be negative). */
    public Datetime addSeconds(int additionalSeconds) {
        return new Datetime(value.plusSeconds(additionalSeconds));
    }

    // ------------------------------------------------------------------
    // Epoch
    // ------------------------------------------------------------------

    /**
     * Returns the number of milliseconds since the Unix epoch
     * (1970-01-01T00:00:00 UTC) for this value, using the system default zone.
     */
    public long getTime() {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------
    // Component accessors
    // ------------------------------------------------------------------

    /** Returns the year component. */
    public int year() {
        return value.getYear();
    }

    /** Returns the month component (1-12). */
    public int month() {
        return value.getMonthValue();
    }

    /** Returns the day-of-month component (1-31). */
    public int day() {
        return value.getDayOfMonth();
    }

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
    // Formatting
    // ------------------------------------------------------------------

    /**
     * Returns the Datetime as a String using the locale and default format of
     * the context user. We approximate with a medium localized style.
     */
    public String format() {
        return value.format(
                DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                        .withLocale(Locale.getDefault()));
    }

    /**
     * Returns the Datetime as a String using the supplied Java
     * {@link DateTimeFormatter} pattern (e.g. {@code "yyyy-MM-dd HH:mm:ss"}).
     */
    public String format(String fmt) {
        return value.format(DateTimeFormatter.ofPattern(fmt));
    }

    /**
     * Same as {@link #format(String)} but rendered in the given time zone
     * (e.g. {@code "GMT"}, {@code "America/Sao_Paulo"}).
     */
    public String format(String fmt, String timezone) {
        return value.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneId.of(timezone))
                .format(DateTimeFormatter.ofPattern(fmt));
    }

    // ------------------------------------------------------------------
    // Equality / ordering
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Datetime)) {
            return false;
        }
        return value.equals(((Datetime) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Apex renders a Datetime as {@code yyyy-MM-dd HH:mm:ss}. */
    @Override
    public String toString() {
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
