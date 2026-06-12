// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Id-keying list constructor mirrors Apex {@code new Map<Id, SObject>(records)}: it keys
 * each record by its Id field. SOQL results are typed {@code List<SObject>}, so the constructor
 * must accept any SObject collection — generics invariance forbids binding the parameter to V,
 * since a {@code List<SObject>} is not a {@code Collection<? extends Receipt>} for a typed V.
 * Each record must land under its own Id key, still typed as V.
 */
class MapTest {

    /** A generated typed sObject is a subclass of SObject; this stands in for one (e.g. Receipt__c). */
    static final class Receipt extends SObject {
        Receipt(String id) {
            super("Receipt__c", "Id", id);
        }
    }

    @Test
    void listConstructor_keysSObjectListById() {
        // List<SObject> (the SOQL/gateway result shape) into a Map with a TYPED value (Receipt):
        // the constructor must bind to SObject, not V, or this won't even compile.
        List<SObject> records = new List<>();
        SObject a = new Receipt("a01AAA");
        SObject b = new Receipt("a01BBB");
        records.add(a);
        records.add(b);

        Map<String, Receipt> byId = new Map<>(records);

        assertEquals(2, byId.size());
        assertSame(a, byId.get("a01AAA"));
        assertSame(b, byId.get("a01BBB"));
    }

    @Test
    void listConstructor_acceptsTypedSubclassList() {
        // A List<Receipt> (typed-subclass collection) must work too — same SObject-bound parameter.
        List<Receipt> records = new List<>();
        Receipt a = new Receipt("a02AAA");
        records.add(a);

        Map<String, Receipt> byId = new Map<>(records);

        assertEquals(1, byId.size());
        assertSame(a, byId.get("a02AAA"));
    }

    @Test
    void values_returnsAlloyxListWithAllValues() {
        // Apex Map.values() returns List<V>. Our override must hand back an alloyx List (so a
        // `List<V> l = m.values();` assignment in generated Java compiles) holding every value.
        Map<String, Integer> m = new Map<>();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);

        Object values = m.values();
        assertTrue(values instanceof List, "values() must return an alloyx List, got " + values.getClass());

        @SuppressWarnings("unchecked")
        List<Integer> list = (List<Integer>) values;
        assertEquals(3, list.size());
        assertTrue(list.contains(1));
        assertTrue(list.contains(2));
        assertTrue(list.contains(3));
    }

    @Test
    void keySet_returnsAlloyxSetWithAllKeys() {
        // Apex Map.keySet() returns Set<K>. Our override must hand back an alloyx Set holding
        // every key (so a `Set<K> ks = m.keySet();` assignment in generated Java compiles).
        Map<String, Integer> m = new Map<>();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);

        Object keys = m.keySet();
        assertTrue(keys instanceof Set, "keySet() must return an alloyx Set, got " + keys.getClass());

        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) keys;
        assertEquals(3, set.size());
        assertTrue(set.contains("a"));
        assertTrue(set.contains("b"));
        assertTrue(set.contains("c"));
    }

    @Test
    void values_isSnapshot_mutatingReturnedListDoesNotChangeMap() {
        // Apex semantics: values() is a snapshot, not a live view. Mutating the returned List
        // must not touch the map (unlike HashMap.values(), whose remove() writes through).
        Map<String, Integer> m = new Map<>();
        m.put("a", 1);
        m.put("b", 2);

        List<Integer> list = m.values();
        list.clear();
        list.add(99);

        assertEquals(2, m.size());
        assertEquals(Integer.valueOf(1), m.get("a"));
        assertEquals(Integer.valueOf(2), m.get("b"));
    }

    @Test
    void keySet_isSnapshot_mutatingReturnedSetDoesNotChangeMap() {
        // Apex semantics: keySet() is a snapshot. Removing from it must not drop the map entry
        // (unlike HashMap.keySet(), whose remove() writes through to the map).
        Map<String, Integer> m = new Map<>();
        m.put("a", 1);
        m.put("b", 2);

        Set<String> keys = m.keySet();
        keys.remove("a");
        keys.add("z");

        assertEquals(2, m.size());
        assertTrue(m.containsKey("a"));
        assertTrue(m.containsKey("b"));
        assertFalse(m.containsKey("z"));
    }
}
