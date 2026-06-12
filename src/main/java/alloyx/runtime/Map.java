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
}
