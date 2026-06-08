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
        if (!Objects.equals(expected, actual)) {
            throw new AssertException("Assertion Failed: Expected: " + expected + ", Actual: " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertException(message);
        }
    }

    public static void assertNotEquals(Object a, Object b) {
        if (Objects.equals(a, b)) {
            throw new AssertException("Assertion Failed: Same value: " + a);
        }
    }
}
