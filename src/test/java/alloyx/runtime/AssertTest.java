// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Direct behavior of the runtime {@link Assert} (the modern Apex test-assertion API). It must
 * share the SAME failure family ({@link AssertException}) and the SAME Apex value-equality as
 * {@link System}'s older assert* methods — including reconciling an Integer against a Decimal,
 * which Java's {@code equals} never does. Identifiers/values are neutral fixtures.
 */
class AssertTest {

    @Test
    void areEqual_passesOnEqual_throwsOnUnequalWithBothValues() {
        assertDoesNotThrow(() -> Assert.areEqual(5, 5));
        AssertException ex = assertThrows(AssertException.class, () -> Assert.areEqual(5, 6));
        // the failure carries BOTH values, like System.assertEquals
        assertTrue(ex.getMessage().contains("5"), ex.getMessage());
        assertTrue(ex.getMessage().contains("6"), ex.getMessage());
    }

    @Test
    void areEqual_failureIsSameExceptionFamilyAsSystemAssertEquals() {
        Class<?> assertFailure =
            assertThrows(AssertException.class, () -> System.assertEquals(1, 2)).getClass();
        Class<?> assertClassFailure =
            assertThrows(AssertException.class, () -> Assert.areEqual(1, 2)).getClass();
        assertEquals(assertFailure, assertClassFailure);
    }

    @Test
    void areEqual_reconcilesIntegerAndDecimalByValue() {
        // Apex: Assert.areEqual(3, someDecimal3) passes. Java's Objects.equals(Integer, BigDecimal)
        // is false, so this proves the numeric reconciliation (BigDecimal.compareTo) is in effect.
        assertDoesNotThrow(() -> Assert.areEqual(3, new Decimal("3")));
        assertDoesNotThrow(() -> Assert.areEqual(3, new Decimal("3.00")));  // scale-insensitive
        assertDoesNotThrow(() -> Assert.areEqual(new Decimal("3"), 3));
        assertThrows(AssertException.class, () -> Assert.areEqual(3, new Decimal("4")));
    }

    @Test
    void areNotEqual_passesOnDifferent_throwsOnSame() {
        assertDoesNotThrow(() -> Assert.areNotEqual(1, 2));
        assertThrows(AssertException.class, () -> Assert.areNotEqual(2, 2));
        // numeric reconciliation applies here too: 2 and Decimal 2 ARE equal, so areNotEqual fails
        assertThrows(AssertException.class, () -> Assert.areNotEqual(2, new Decimal("2")));
    }

    @Test
    void isTrue_isFalse() {
        assertDoesNotThrow(() -> Assert.isTrue(true));
        assertThrows(AssertException.class, () -> Assert.isTrue(false));
        assertThrows(AssertException.class, () -> Assert.isTrue(null));
        assertDoesNotThrow(() -> Assert.isFalse(false));
        assertThrows(AssertException.class, () -> Assert.isFalse(true));
        assertThrows(AssertException.class, () -> Assert.isFalse(null));
    }

    @Test
    void isNull_isNotNull() {
        assertDoesNotThrow(() -> Assert.isNull(null));
        assertThrows(AssertException.class, () -> Assert.isNull("x"));
        assertDoesNotThrow(() -> Assert.isNotNull("x"));
        assertThrows(AssertException.class, () -> Assert.isNotNull(null));
    }

    @Test
    void fail_withAndWithoutMessage() {
        assertThrows(AssertException.class, () -> Assert.fail());
        AssertException ex = assertThrows(AssertException.class, () -> Assert.fail("boom"));
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void isInstanceOfType_happyAndFailing() {
        // a String value IS a String; a runtime Decimal IS a Decimal -> no throw
        assertDoesNotThrow(() -> Assert.isInstanceOfType("hello", Type.forName("String")));
        assertDoesNotThrow(() -> Assert.isInstanceOfType(new Decimal("1"), Type.forName("Decimal")));
        // a String value is NOT an Integer -> throws
        assertThrows(AssertException.class,
            () -> Assert.isInstanceOfType("hello", Type.forName("Integer")));
        // a type that can't be resolved locally fails clearly (never a vacuous pass)
        assertThrows(AssertException.class,
            () -> Assert.isInstanceOfType("hello", Type.forName("SomeOrgOnlyType__c")));
    }
}
