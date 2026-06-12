// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Iterable<T>}: a class usable as the source of a {@code for (T x : iterable)} loop
 * (and a custom batch's start()). It hands out an {@link Iterator} over {@code T}.
 *
 * <p>DESIGN: this is an alloyx runtime interface, NOT {@code java.lang.Iterable}, because its
 * cursor is the Apex-shaped {@link Iterator} (whose {@code hasNext()} returns {@code Boolean},
 * not {@code boolean}) rather than {@code java.util.Iterator}. The transpiler emits
 * {@code Iterator<T> iterator()}, which matches this signature exactly.
 */
public interface Iterable<T> {
    Iterator<T> iterator();
}
