// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex's {@code Assert} class (the modern test-assertion API: {@code Assert.areEqual},
 * {@code Assert.isTrue}, ...). It mirrors the older {@code System.assert*} family and is
 * the form most current test code uses. Every check delegates to the SAME comparison and
 * failure machinery as {@link System} — {@link AssertException} on failure, with both
 * values in the message, and Apex value-equality that reconciles Integer vs Decimal — so
 * the two APIs can never disagree on what "equal" means or how a failure is reported.
 */
public final class Assert {
    private Assert() {
    }

    public static void areEqual(Object expected, Object actual) {
        if (!System.apexEquals(expected, actual)) {
            throw new AssertException("Assertion Failed: Expected: " + expected + ", Actual: " + actual);
        }
    }

    public static void areEqual(Object expected, Object actual, String message) {
        if (!System.apexEquals(expected, actual)) {
            throw new AssertException(message + " (Expected: " + expected + ", Actual: " + actual + ")");
        }
    }

    public static void areNotEqual(Object notExpected, Object actual) {
        if (System.apexEquals(notExpected, actual)) {
            throw new AssertException("Assertion Failed: Same value: " + actual);
        }
    }

    public static void areNotEqual(Object notExpected, Object actual, String message) {
        if (System.apexEquals(notExpected, actual)) {
            throw new AssertException(message + " (Same value: " + actual + ")");
        }
    }

    public static void isTrue(Boolean condition) {
        if (condition == null || !condition) {
            throw new AssertException("Assertion Failed");
        }
    }

    public static void isTrue(Boolean condition, String message) {
        if (condition == null || !condition) {
            throw new AssertException(message);
        }
    }

    public static void isFalse(Boolean condition) {
        if (condition == null || condition) {
            throw new AssertException("Assertion Failed");
        }
    }

    public static void isFalse(Boolean condition, String message) {
        if (condition == null || condition) {
            throw new AssertException(message);
        }
    }

    public static void isNull(Object value) {
        if (value != null) {
            throw new AssertException("Assertion Failed: Expected null, Actual: " + value);
        }
    }

    public static void isNull(Object value, String message) {
        if (value != null) {
            throw new AssertException(message);
        }
    }

    public static void isNotNull(Object value) {
        if (value == null) {
            throw new AssertException("Assertion Failed: Expected non-null value");
        }
    }

    public static void isNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertException(message);
        }
    }

    public static void fail() {
        throw new AssertException("Assertion Failed");
    }

    public static void fail(String message) {
        throw new AssertException(message);
    }

    /**
     * Apex {@code Assert.isInstanceOfType(value, expectedType)}: assert {@code value} is an
     * instance of the type named by {@code expectedType} (a {@link Type} token). The check
     * is faithful where the type resolves to a real local Java class (the runtime types and
     * the generated sObject classes are loadable by name); when it can't be resolved locally
     * (an org-only type), it degrades to a clear failure rather than a silent pass.
     */
    public static void isInstanceOfType(Object value, Type expectedType) {
        isInstanceOfType(value, expectedType, null);
    }

    public static void isInstanceOfType(Object value, Type expectedType, String message) {
        String typeName = expectedType == null ? null : expectedType.getName();
        if (!isInstanceOf(value, typeName)) {
            String detail = "Assertion Failed: " + value + " is not an instance of " + typeName;
            throw new AssertException(message == null ? detail : message);
        }
    }

    // Whether `value` is an instance of the type named `typeName`. Resolves the name against the
    // runtime/sObject classes the same way the rest of the runtime does (case-insensitive simple
    // names, default-package classes by their bare name). An unresolvable name yields false, so a
    // type AlloyX can't see locally fails the assert clearly instead of passing vacuously.
    private static boolean isInstanceOf(Object value, String typeName) {
        if (value == null || typeName == null) {
            return false;
        }
        Class<?> type = resolve(typeName.trim());
        return type != null && type.isInstance(value);
    }

    private static Class<?> resolve(String typeName) {
        // generic-type tokens (List<...>) compare on the raw collection
        int lt = typeName.indexOf('<');
        String base = lt >= 0 ? typeName.substring(0, lt).trim() : typeName;
        Class<?> scalar = scalarClass(base);
        if (scalar != null) {
            return scalar;
        }
        // a runtime type (Decimal, Date, SObjectType, ...) or a generated sObject class loaded by
        // its simple name. Try the runtime package first, then the default package (generated
        // sObject/user classes live there), then a fully-qualified name as given.
        for (String fqn : new String[]{"alloyx.runtime." + base, base}) {
            try {
                return Class.forName(fqn);
            } catch (ClassNotFoundException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static Class<?> scalarClass(String base) {
        return switch (base.toLowerCase(java.util.Locale.ROOT)) {
            case "string", "id" -> String.class;
            case "integer" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "boolean" -> Boolean.class;
            case "decimal" -> Decimal.class;
            case "object" -> Object.class;
            default -> null;
        };
    }
}
