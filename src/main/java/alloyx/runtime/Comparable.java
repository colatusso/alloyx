// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Comparable}: implemented to make a class sortable (used by {@code List.sort()}).
 *
 * <p>DESIGN: this is an alloyx runtime interface, NOT {@code java.lang.Comparable}. Apex's
 * {@code compareTo} returns {@code Integer}, and the transpiler emits exactly that — a method
 * {@code Integer compareTo(Object o)}. {@code java.lang.Comparable<Object>} requires the
 * primitive {@code int compareTo(Object)}; a boxed {@code Integer} return does NOT override it
 * (return-type covariance doesn't bridge {@code Integer} -> {@code int}). Mapping to the Java
 * interface would therefore fail to compile. So we declare a faithful Apex-shaped interface
 * whose method signature matches what the emitter produces, and it compiles cleanly.
 */
public interface Comparable {
    Integer compareTo(Object o);
}
