// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
