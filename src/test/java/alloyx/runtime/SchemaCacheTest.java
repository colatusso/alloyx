// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The describe filter must keep only real sObjects. A code scan turns up hundreds of
 * Apex classes, mocks, wrappers, relationship names and keywords; the org's global
 * describe is the authoritative gate (Apex classes and standard objects look identical
 * by name), plus the cheap {@code __r} relationship drop.
 */
class SchemaCacheTest {

    @TempDir
    Path dir;

    /** Org that knows only Account + Contact, and can describe Account. */
    static final class Org implements OrgGateway {
        @Override
        public List<SObject> query(String soql, Map<String, Object> binds) {
            return new List<>();
        }

        @Override
        public void insert(List<SObject> records) {
        }

        @Override
        public void update(List<SObject> records) {
        }

        @Override
        public void delete(List<SObject> records) {
        }

        @Override
        public Map<String, String> describe(String sobjectType) {
            if (!sobjectType.equals("Account")) {
                throw new RuntimeException("404 NOT_FOUND: " + sobjectType);
            }
            return Map.of("Name", "String");
        }

        @Override
        public Set<String> globalSObjects() {
            return Set.of("Account", "Contact");
        }
    }

    private SchemaCache cache() {
        return new SchemaCache(new Org(), dir, Long.MAX_VALUE);
    }

    @Test
    void globalListGatesRealObjects() {
        SchemaCache cache = cache();
        assertTrue(cache.isKnownSObject("Account"));
        assertTrue(cache.isKnownSObject("contact"), "membership is case-insensitive");
        assertFalse(cache.isKnownSObject("PromotionResult"), "an Apex class is not an sObject");
    }

    @Test
    void isDescribedRejectsNonObjectsWithoutDescribing() {
        SchemaCache cache = cache();
        assertFalse(cache.isDescribed("CustomerInvoices__r"), "relationship name");
        assertFalse(cache.isDescribed("IContactService"), "interface / not in global list");
        assertTrue(cache.isDescribed("Account"));
    }
}
