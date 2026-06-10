// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Apex {@code Decimal} runtime type.
 *
 * <p>It extends {@link java.math.BigDecimal} so a {@code Decimal} value flows
 * anywhere a number or {@code Object} is expected and inherits BigDecimal's
 * arithmetic/inspection methods (add, subtract, multiply, scale, precision,
 * compareTo, equals, hashCode, toString, toPlainString, ...). On top of that
 * inheritance we add the Apex-specific static factories and the instance
 * methods whose name or return type differs from BigDecimal.
 *
 * <p>Apex semantics honored here:
 * <ul>
 *   <li>The default rounding mode for {@code setScale}/{@code divide} is
 *       {@link RoundingMode#HALF_EVEN} (Apex "round half to even").</li>
 *   <li>{@code round()} / {@code round(RoundingMode)} return a {@code Long}
 *       (the rounded whole-number value), matching the Apex reference.</li>
 *   <li>{@code valueOf(Object)} is lenient, accepting String/Number/BigDecimal.</li>
 * </ul>
 *
 * <p>Known limitation (transpiler concern, not solved here): Apex operator
 * arithmetic such as {@code a + b} has no Java operator on BigDecimal.
 */
public class Decimal extends BigDecimal {

    private static final long serialVersionUID = 1L;

    /** Apex's default rounding when none is supplied. */
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    // ------------------------------------------------------------------
    // Constructors (BigDecimal has no no-arg ctor, so we must delegate).
    // ------------------------------------------------------------------

    public Decimal(String s) {
        super(s);
    }

    public Decimal(long l) {
        super(l);
    }

    public Decimal(double d) {
        // Mirror Apex/string semantics: avoid binary-float noise like 0.1 -> 0.1000000...
        super(Double.toString(d));
    }

    /** Internal: build a Decimal from an unscaled value + scale via the string form. */
    public Decimal(BigInteger unscaled, int scale) {
        super(new BigDecimal(unscaled, scale).toPlainString());
    }

    /** Internal helper: wrap an existing BigDecimal as a Decimal without losing scale. */
    private static Decimal wrap(BigDecimal b) {
        if (b instanceof Decimal) {
            return (Decimal) b;
        }
        return new Decimal(b.toPlainString());
    }

    // ------------------------------------------------------------------
    // Static factories — the transpiler emits Decimal.valueOf(...).
    // ------------------------------------------------------------------

    /** Apex {@code Decimal.valueOf(String)}: parse a decimal literal, e.g. "10.50". */
    public static Decimal valueOf(String s) {
        return new Decimal(s);
    }

    /** Apex {@code Decimal.valueOf(Double)}. */
    public static Decimal valueOf(double d) {
        return new Decimal(d);
    }

    /** Apex {@code Decimal.valueOf(Long)} (also covers int via widening). */
    public static Decimal valueOf(long l) {
        return new Decimal(l);
    }

    /**
     * Lenient factory used when the static type is unknown. Accepts
     * String / Decimal / BigDecimal / any Number / null.
     */
    public static Decimal valueOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Decimal) {
            return (Decimal) o;
        }
        if (o instanceof BigDecimal) {
            return wrap((BigDecimal) o);
        }
        if (o instanceof BigInteger) {
            return new Decimal(o.toString());
        }
        if (o instanceof Double || o instanceof Float) {
            return new Decimal(((Number) o).doubleValue());
        }
        if (o instanceof Number) {
            // Integer, Long, Short, Byte, AtomicInteger, etc.
            return new Decimal(((Number) o).longValue());
        }
        // Strings and anything else: parse its textual form.
        return new Decimal(o.toString().trim());
    }

    // ------------------------------------------------------------------
    // setScale — Apex defaults to HALF_EVEN when no mode is given.
    // ------------------------------------------------------------------

    /** Apex {@code setScale(Integer)} using the default HALF_EVEN rounding. */
    public Decimal setScale(int scale) {
        return wrap(super.setScale(scale, DEFAULT_ROUNDING));
    }

    /** Apex {@code setScale(Integer, System.RoundingMode)}. */
    @Override
    public Decimal setScale(int scale, RoundingMode roundingMode) {
        return wrap(super.setScale(scale, roundingMode));
    }

    // ------------------------------------------------------------------
    // divide — Apex always wants a target scale (BigDecimal would throw
    // ArithmeticException on a non-terminating quotient otherwise).
    // ------------------------------------------------------------------

    /** Apex {@code divide(Decimal, Integer)}: quotient at the given scale, HALF_EVEN. */
    public Decimal divide(Decimal divisor, int scale) {
        return divide(divisor, scale, DEFAULT_ROUNDING);
    }

    /** Apex {@code divide(Decimal, Integer, System.RoundingMode)}. */
    public Decimal divide(Decimal divisor, int scale, RoundingMode roundingMode) {
        return wrap(super.divide(divisor, scale, roundingMode));
    }

    // ------------------------------------------------------------------
    // Other Apex instance methods that return Decimal.
    // ------------------------------------------------------------------

    /** Apex {@code pow(Integer)}: raise this value to a non-negative power. */
    public Decimal pow(int exponent) {
        return wrap(super.pow(exponent));
    }

    /** Apex {@code abs()}: absolute value. */
    @Override
    public Decimal abs() {
        return wrap(super.abs());
    }

    /** Apex {@code stripTrailingZeros()}: remove trailing fractional zeros. */
    @Override
    public Decimal stripTrailingZeros() {
        return wrap(super.stripTrailingZeros());
    }

    // ------------------------------------------------------------------
    // round — in Apex this collapses to a whole number and returns Long.
    // ------------------------------------------------------------------

    /** Apex {@code round()}: round to the nearest Long using HALF_EVEN. */
    public Long round() {
        return round(DEFAULT_ROUNDING);
    }

    /** Apex {@code round(System.RoundingMode)}: round to the nearest Long. */
    public Long round(RoundingMode roundingMode) {
        return super.setScale(0, roundingMode).longValueExact();
    }

    // ------------------------------------------------------------------
    // Numeric extraction. doubleValue()/intValue()/longValue() are
    // inherited from BigDecimal; intValue() is re-exposed only for clarity.
    // ------------------------------------------------------------------

    /** Apex {@code intValue()}: integer part as an int (truncating). */
    @Override
    public int intValue() {
        return super.intValue();
    }

    /** Apex {@code longValue()}: integer part as a long (truncating). */
    @Override
    public long longValue() {
        return super.longValue();
    }

    // ------------------------------------------------------------------
    // format — Apex returns a locale-formatted String with grouping.
    // ------------------------------------------------------------------

    /**
     * Apex {@code format()}: render using the running user's locale, inserting
     * grouping separators and preserving this value's fractional digits.
     */
    public String format() {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        nf.setGroupingUsed(true);
        int s = Math.max(scale(), 0);
        nf.setMinimumFractionDigits(s);
        nf.setMaximumFractionDigits(s);
        return nf.format(this);
    }

    // ------------------------------------------------------------------
    // Convenience overrides so chained results stay typed as Decimal.
    // (BigDecimal's own add/subtract/multiply return BigDecimal, which is
    //  acceptable per the task; these typed variants are extras for callers
    //  that pass a Decimal explicitly.)
    // ------------------------------------------------------------------

    public Decimal add(Decimal other) {
        return wrap(super.add(other));
    }

    public Decimal subtract(Decimal other) {
        return wrap(super.subtract(other));
    }

    public Decimal multiply(Decimal other) {
        return wrap(super.multiply(other));
    }

    /** Apex {@code negate()}: arithmetic negation. */
    @Override
    public Decimal negate() {
        return wrap(super.negate());
    }
}
