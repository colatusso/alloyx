// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Faithful runtime implementation of the Apex {@code System.Date} type.
 *
 * <p>Apex {@code Date} represents a calendar date with no time component and no
 * time zone. It is backed here by {@link java.time.LocalDate}.</p>
 *
 * <p>Reference:
 * <a href="https://developer.salesforce.com/docs/atlas.en-us.apexref.meta/apexref/apex_methods_system_date.htm">
 * Apex Date class</a>.</p>
 */
public final class Date {

    /** Underlying immutable date value. Never {@code null}. */
    private final LocalDate value;

    private Date(LocalDate value) {
        this.value = value;
    }

    /** Wraps an existing {@link LocalDate}. Package use. */
    static Date of(LocalDate value) {
        return new Date(value);
    }

    /** Exposes the underlying {@link LocalDate}. Package use. */
    LocalDate toLocalDate() {
        return value;
    }

    // ------------------------------------------------------------------
    // Static constructors
    // ------------------------------------------------------------------

    /** Returns the current date in the running user's time zone. */
    public static Date today() {
        return new Date(LocalDate.now());
    }

    /**
     * Constructs a Date from year, month and day components.
     * Mirrors {@code Date.newInstance(year, month, day)}.
     */
    public static Date newInstance(int year, int month, int day) {
        return new Date(LocalDate.of(year, month, day));
    }

    /**
     * Parses a String into a Date.
     *
     * <p>Apex accepts both the standard date format {@code "yyyy-MM-dd"} and a
     * datetime format {@code "yyyy-MM-dd HH:mm:ss"}, in which case only the date
     * portion is used.</p>
     */
    public static Date valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Date.valueOf: argument cannot be null");
        }
        String trimmed = s.trim();
        // Take the date part if a time component is present (space or 'T' separator).
        int sep = trimmed.indexOf(' ');
        if (sep < 0) {
            sep = trimmed.indexOf('T');
        }
        String datePart = (sep >= 0) ? trimmed.substring(0, sep) : trimmed;
        return new Date(LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE));
    }

    /** Returns true if the given year is a leap year. */
    public static boolean isLeapYear(int year) {
        return LocalDate.of(year, 1, 1).isLeapYear();
    }

    // ------------------------------------------------------------------
    // Arithmetic (return a new Date; Date is immutable)
    // ------------------------------------------------------------------

    /** Adds the given number of days (may be negative). */
    public Date addDays(int additionalDays) {
        return new Date(value.plusDays(additionalDays));
    }

    /** Adds the given number of months (may be negative). */
    public Date addMonths(int additionalMonths) {
        return new Date(value.plusMonths(additionalMonths));
    }

    /** Adds the given number of years (may be negative). */
    public Date addYears(int additionalYears) {
        return new Date(value.plusYears(additionalYears));
    }

    // ------------------------------------------------------------------
    // Differences
    // ------------------------------------------------------------------

    /**
     * Returns the number of days between this date and {@code other}.
     * Positive when {@code other} is later than this date.
     */
    public int daysBetween(Date other) {
        return (int) ChronoUnit.DAYS.between(this.value, other.value);
    }

    /**
     * Returns the number of months between this date and {@code other},
     * ignoring the difference in days (matches Apex semantics).
     * Positive when {@code other} is later than this date.
     */
    public int monthsBetween(Date other) {
        return (other.value.getYear() - this.value.getYear()) * 12
                + (other.value.getMonthValue() - this.value.getMonthValue());
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

    /**
     * Returns the day of the week as the English name (e.g. "Monday").
     * Apex returns the full weekday name in the user's locale; we default to
     * English to keep behavior deterministic.
     */
    public String dayOfWeek() {
        return value.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** Returns the first day of the month for this date. */
    public Date toStartOfMonth() {
        return new Date(value.withDayOfMonth(1));
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * Returns the date as a String using the locale of the context user.
     * We render with the medium locale-sensitive date style for the default
     * locale, approximating Apex's locale-based {@code format()}.
     */
    public String format() {
        return value.format(
                DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                        .withLocale(Locale.getDefault()));
    }

    // ------------------------------------------------------------------
    // Equality / ordering
    // ------------------------------------------------------------------

    /** True if {@code other} represents the same calendar day. */
    public boolean isSameDay(Date other) {
        return other != null && this.value.equals(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Date)) {
            return false;
        }
        return value.equals(((Date) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Apex renders a Date as {@code yyyy-MM-dd HH:mm:ss} with a zeroed time. */
    @Override
    public String toString() {
        return value.format(DateTimeFormatter.ISO_LOCAL_DATE) + " 00:00:00";
    }
}
