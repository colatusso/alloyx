// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex Map<K,V> over java.util.HashMap. Apex put/get/containsKey/keySet/values/
 * size/isEmpty map directly; get() returns null for a missing key, like Apex.
 * Iteration order is intentionally not guaranteed (mirrors Apex).
 */
public class Map<K, V> extends java.util.HashMap<K, V> {
    public Map() {
        super();
    }

    /** Apex: new Map<Id, SObject>(records) — keyed by each record's Id field. */
    @SuppressWarnings("unchecked")
    public Map(java.util.Collection<? extends SObject> records) {
        for (SObject so : records) {
            put((K) so.get("Id"), (V) so);
        }
    }

    /** Apex: new Map<K, V>(otherMap) — copy constructor. */
    public Map(java.util.Map<? extends K, ? extends V> source) {
        super(source);
    }

    /**
     * Apex {@code values()} returns a {@code List<V>} snapshot — a fresh list, not a live view.
     * HashMap's inherited {@code values()} hands back a write-through {@code Collection<V>} view,
     * which (a) isn't an alloyx List so generated {@code List<V> l = m.values();} won't compile,
     * and (b) wouldn't be a snapshot. Materialize an alloyx copy. Covariant override: alloyx List
     * is-a java.util.Collection.
     */
    @Override
    public List<V> values() {
        return new List<>(super.values());
    }

    /**
     * Apex {@code keySet()} returns a {@code Set<K>} snapshot. Same reasoning as {@link #values()}:
     * materialize an alloyx Set copy so the assignment compiles and mutation doesn't write through.
     * Covariant override: alloyx Set is-a java.util.Set.
     */
    @Override
    public Set<K> keySet() {
        return new Set<>(super.keySet());
    }
}
