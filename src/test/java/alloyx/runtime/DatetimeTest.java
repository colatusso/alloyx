// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

/**
 * Apex {@code Datetime} is an absolute instant, so every assertion here is
 * machine/zone-independent: expected local values are computed via java.time at
 * test time (never hardcoded against a particular host zone). Only true GMT and
 * epoch facts are asserted against constants.
 */
class DatetimeTest {

    private static final ZoneId LOCAL = ZoneId.systemDefault();

    /** Known instant: 2024-01-15T10:30:00Z == 1705314600000 epoch millis. */
    private static final long KNOWN_EPOCH_MILLIS = 1705314600000L;
    private static final Instant KNOWN = Instant.ofEpochMilli(KNOWN_EPOCH_MILLIS);

    @Test
    void newInstanceGmtIsAbsoluteEpoch() {
        // Machine-independent: the GMT components map to a fixed instant.
        assertEquals(KNOWN_EPOCH_MILLIS,
                Datetime.newInstanceGmt(2024, 1, 15, 10, 30, 0).getTime());
    }

    @Test
    void localVsGmtConstructionDiffersByLocalOffset() {
        // Identical wall-clock components: local interprets in the host zone, gmt in UTC.
        // Their epoch gap is exactly the host zone's offset at that local time.
        long local = Datetime.newInstance(2024, 1, 15, 10, 30, 0).getTime();
        long gmt = Datetime.newInstanceGmt(2024, 1, 15, 10, 30, 0).getTime();
        long expectedOffsetMillis = LOCAL.getRules()
                .getOffset(LocalDateTime.of(2024, 1, 15, 10, 30, 0))
                .getTotalSeconds() * 1000L;
        assertEquals(expectedOffsetMillis, gmt - local);
    }

    @Test
    void gmtAccessorsRenderUtcAndLocalIsOffsetShifted() {
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        // GMT components are fixed facts of the instant.
        assertEquals(2024, dt.yearGmt());
        assertEquals(1, dt.monthGmt());
        assertEquals(15, dt.dayGmt());
        assertEquals(10, dt.hourGmt());
        assertEquals(30, dt.minuteGmt());
        assertEquals(0, dt.secondGmt());
        // Local components must equal the instant rendered in the host zone (computed).
        var expectedLocal = KNOWN.atZone(LOCAL);
        assertEquals(expectedLocal.getYear(), dt.year());
        assertEquals(expectedLocal.getMonthValue(), dt.month());
        assertEquals(expectedLocal.getDayOfMonth(), dt.day());
        assertEquals(expectedLocal.getHour(), dt.hour());
        assertEquals(expectedLocal.getMinute(), dt.minute());
        assertEquals(expectedLocal.getSecond(), dt.second());
    }

    @Test
    void formatGmtIsExactString() {
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        assertEquals("2024-01-15 10:30:00", dt.formatGmt("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    void formatWithFixedZoneIsDeterministic() {
        // America/Sao_Paulo is UTC-03:00 (no DST in 2024) -> deterministic on any host.
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        assertEquals("2024-01-15 07:30:00",
                dt.format("yyyy-MM-dd HH:mm:ss", "America/Sao_Paulo"));
    }

    @Test
    void formatLocalMatchesComputedLocalRendering() {
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        String expected = KNOWN.atZone(LOCAL)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertEquals(expected, dt.format("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    void addHoursIsPureInstantOffset() {
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        assertEquals(KNOWN_EPOCH_MILLIS + 3 * 3600_000L, dt.addHours(3).getTime());
    }

    @Test
    void addDaysAcrossSystemDstDoesNotCorruptInstant() {
        // Adding calendar days is GMT-based: exactly N*86400s on the instant, regardless
        // of any DST transition the host zone may cross in that span.
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        assertEquals(KNOWN_EPOCH_MILLIS + 30 * 86400_000L, dt.addDays(30).getTime());
    }

    @Test
    void addMonthsIsGmtCalendarBased() {
        // addMonths(1) on 2024-01-15T10:30:00Z -> 2024-02-15T10:30:00Z (GMT calendar).
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        long expected = LocalDateTime.of(2024, 2, 15, 10, 30, 0)
                .atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(expected, dt.addMonths(1).getTime());
    }

    @Test
    void stringRoundTripPreservesInstant() {
        // The canonical Datetime string (toString / format yyyy-MM-dd HH:mm:ss) is rendered
        // in local zone; valueOf re-interprets in local zone -> same instant. This is the
        // representation that flows to/from SObject fields and JSON as a String.
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        assertEquals(dt.getTime(), Datetime.valueOf(dt.toString()).getTime());
    }

    @Test
    void gmtStringRoundTripPreservesInstant() {
        // Org REST datetimes are UTC-based; formatGmt + valueOfGmt must round-trip exactly.
        Datetime dt = Datetime.newInstance(KNOWN_EPOCH_MILLIS);
        String gmtText = dt.formatGmt("yyyy-MM-dd HH:mm:ss");
        assertEquals(dt.getTime(), Datetime.valueOfGmt(gmtText).getTime());
    }

    @Test
    void valueOfVsValueOfGmtDifferByLocalOffset() {
        String text = "2024-01-15 10:30:00";
        long local = Datetime.valueOf(text).getTime();
        long gmt = Datetime.valueOfGmt(text).getTime();
        long expectedOffsetMillis = LOCAL.getRules()
                .getOffset(LocalDateTime.of(2024, 1, 15, 10, 30, 0))
                .getTotalSeconds() * 1000L;
        assertEquals(expectedOffsetMillis, gmt - local);
    }

    @Test
    void equalityComparesInstants() {
        assertEquals(Datetime.newInstance(KNOWN_EPOCH_MILLIS),
                Datetime.newInstanceGmt(2024, 1, 15, 10, 30, 0));
    }
}
