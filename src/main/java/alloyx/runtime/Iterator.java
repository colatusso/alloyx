// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Iterator<T>}: the cursor a custom {@link Iterable} hands out (hasNext/next).
 *
 * <p>DESIGN: this is an alloyx runtime interface, NOT {@code java.util.Iterator}. Apex's
 * {@code hasNext()} returns {@code Boolean}, and the transpiler emits exactly that — a method
 * {@code Boolean hasNext()}. {@code java.util.Iterator} requires the primitive
 * {@code boolean hasNext()}; a boxed {@code Boolean} return does NOT override it, so mapping to
 * the Java interface would fail to compile. The faithful Apex-shaped interface matches the
 * emitter's output and compiles. (Apex's Iterator has no {@code remove()}.)
 */
public interface Iterator<T> {
    Boolean hasNext();

    T next();
}
