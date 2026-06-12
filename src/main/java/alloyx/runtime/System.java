// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.Objects;

/**
 * Apex's System namespace. Named exactly "System" so transpiled code reads like
 * Apex; a single-type-import shadows java.lang.System (JLS 6.4.1), so generated
 * classes use {@code System.debug(...)} verbatim. Internally we qualify
 * java.lang.System to avoid self-reference.
 *
 * Note: {@code System.assert(...)} can't be a Java method ({@code assert} is a
 * keyword) — the transpiler maps it to {@link #assertTrue}.
 */
public class System {
    public static void debug(Object value) {
        java.lang.System.out.println("DEBUG|" + value);
    }

    /** Apex {@code System.debug(LoggingLevel, Object)}: the level is advisory locally. */
    public static void debug(Object level, Object value) {
        java.lang.System.out.println("DEBUG|" + value);
    }

    /** Apex {@code System.now()}: current date-time. */
    public static Datetime now() {
        return Datetime.now();
    }

    /** Apex {@code System.today()}: current date. */
    public static Date today() {
        return Date.today();
    }

    public static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertException("Assertion Failed");
        }
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertException(message);
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        if (!apexEquals(expected, actual)) {
            throw new AssertException("Assertion Failed: Expected: " + expected + ", Actual: " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!apexEquals(expected, actual)) {
            throw new AssertException(message);
        }
    }

    public static void assertNotEquals(Object a, Object b) {
        if (apexEquals(a, b)) {
            throw new AssertException("Assertion Failed: Same value: " + a);
        }
    }

    /**
     * Apex value equality for assert comparisons: like {@link Objects#equals} but
     * reconciling numeric types by VALUE, the way Apex does. Apex has no Integer/Decimal
     * distinction at the equality level — {@code 3 == 3.0} and {@code Assert.areEqual(3,
     * someDecimal3)} both hold — but in Java an {@code Integer} and a {@code Decimal}
     * (a {@code BigDecimal}) never {@code .equals} each other (different classes, and
     * BigDecimal.equals is scale-sensitive). So when both sides are numbers we compare by
     * magnitude via BigDecimal.compareTo (scale-insensitive); everything else falls back
     * to Objects.equals. Shared by Assert (which delegates here) so the two never diverge.
     */
    static boolean apexEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return toBigDecimal(na).compareTo(toBigDecimal(nb)) == 0;
        }
        return Objects.equals(a, b);
    }

    private static java.math.BigDecimal toBigDecimal(Number n) {
        if (n instanceof java.math.BigDecimal bd) {
            return bd;
        }
        if (n instanceof java.math.BigInteger bi) {
            return new java.math.BigDecimal(bi);
        }
        if (n instanceof Double || n instanceof Float) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        return java.math.BigDecimal.valueOf(n.longValue());
    }
}
