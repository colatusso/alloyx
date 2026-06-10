// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex Set<T> over java.util.HashSet. Apex add (returns Boolean), contains, size
 * and isEmpty map directly. Iteration order is intentionally not guaranteed
 * (mirrors Apex — do not rely on it).
 */
public class Set<T> extends java.util.HashSet<T> {
    public Set() {
        super();
    }

    public Set(java.util.Collection<? extends T> source) {
        super(source);
    }
}
