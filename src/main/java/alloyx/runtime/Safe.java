// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runtime support for Apex safe navigation ({@code ?.}). The target is evaluated
 * once and passed in; a null target short-circuits to null (for {@link #nav}) or to
 * a no-op (for {@link #run}) instead of dereferencing. Lowering {@code a?.b} to
 * {@code Safe.nav(a, x -> x.b)} evaluates {@code a} exactly once (side effects fire
 * once, deep chains don't blow up the source) and lets javac infer the result type
 * from the lambda body, so no {@code <nulltype>} ternary is ever synthesized.
 */
public final class Safe {
    private Safe() {}

    /** Apex {@code a?.b} / {@code a?.m()} in value position: null target -> null. */
    public static <T, R> R nav(T target, Function<T, R> access) {
        return target == null ? null : access.apply(target);
    }

    /** Apex {@code a?.m()} in statement position (the call may be void): null target -> no-op. */
    public static <T> void run(T target, Consumer<T> action) {
        if (target != null) {
            action.accept(target);
        }
    }
}
