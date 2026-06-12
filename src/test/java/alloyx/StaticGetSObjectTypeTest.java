// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.SchemaProvider;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Item 3: {@code Account.getSObjectType()} / {@code Item__c.getSObjectType()} — called ON THE
 * TYPE. The runtime SObject has an INSTANCE getSObjectType(); a static can't share the signature
 * in the hierarchy, so the call site is rewritten: a bare unshadowed sObject TYPE name with a
 * zero-arg getSObjectType() (case-insensitive) emits the SObjectType token directly. A variable
 * of the same name keeps INSTANCE semantics (Apex variables win over types).
 */
class StaticGetSObjectTypeTest {

    private static final Map<String, String> INV_FIELDS = Map.of("Id", "Id", "Total__c", "Decimal");

    private static final SchemaProvider SCHEMA = new SchemaProvider() {
        @Override public String fieldType(String s, String f) {
            if (!s.equalsIgnoreCase("Inv__c")) return null;
            for (var e : INV_FIELDS.entrySet()) if (e.getKey().equalsIgnoreCase(f)) return e.getValue();
            return null;
        }
        @Override public boolean isDescribed(String s) { return s.equalsIgnoreCase("Inv__c"); }
        @Override public String canonicalField(String s, String f) {
            for (var e : INV_FIELDS.entrySet()) if (e.getKey().equalsIgnoreCase(f)) return e.getKey();
            return f;
        }
        @Override public Map<String, String> fields(String s) {
            return s.equalsIgnoreCase("Inv__c") ? INV_FIELDS : null;
        }
    };

    private String transpileTyped(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), SCHEMA, Set.of("Inv__c")).source();
    }

    @Test
    void typedSObjectStaticGetSObjectType_emitsToken() {
        String java = transpileTyped("""
            public class C {
                public static Object t() { return Inv__c.getSObjectType(); }
            }
            """);
        assertTrue(java.contains("new SObjectType(\"Inv__c\")"), java);
        assertFalse(java.contains("Inv__c.getSObjectType()"), java); // never a static-on-class call
    }

    @Test
    void standardSObjectStaticGetSObjectType_emitsToken() {
        // a standard object that isn't a generated typed class is still a TYPE name here.
        String java = transpileTyped("""
            public class C {
                public static Object t() { return Account.getSObjectType(); }
            }
            """);
        assertTrue(java.contains("new SObjectType(\"Account\")"), java);
    }

    @Test
    void caseInsensitiveMethodName_stillRewrites() {
        String java = transpileTyped("""
            public class C {
                public static Object t() { return Inv__c.getsobjecttype(); }
            }
            """);
        assertTrue(java.contains("new SObjectType(\"Inv__c\")"), java);
    }

    @Test
    void instanceVariableSameName_keepsInstanceCall() {
        // a LOCAL named like the type wins (Apex): the instance getSObjectType() is kept.
        String java = transpileTyped("""
            public class C {
                public static Object t(Inv__c inv) { return inv.getSObjectType(); }
            }
            """);
        assertTrue(java.contains("inv.getSObjectType()"), java);
        assertFalse(java.contains("new SObjectType(\"Inv__c\")"), java);
    }

    @Test
    void emittedTokenStringifiesToApiName() {
        // the runtime SObjectType the rewrite emits carries the API name (used to build SOQL/REST).
        assertEquals("Inv__c", new alloyx.runtime.SObjectType("Inv__c").getName());
    }

    // keep the no-schema shape too: with no typed sObjects, a known sObject name still rewrites
    // (anything that maps to the dynamic SObject is a type-position sObject reference).
    @Test
    void noSchema_unknownObjectName_stillRewrites() {
        ClassDecl cls = Parser.parse("""
            public class C {
                public static Object t() { return MyThing__c.getSObjectType(); }
            }
            """);
        SchemaProvider noSchema = new SchemaProvider() {
            @Override public String fieldType(String s, String f) { return null; }
            @Override public boolean isDescribed(String s) { return false; }
            @Override public String canonicalField(String s, String f) { return f; }
            @Override public Map<String, String> fields(String s) { return null; }
        };
        String java = Transpiler.transpile(cls, Set.of("C"), noSchema, Set.of()).source();
        assertTrue(java.contains("new SObjectType(\"MyThing__c\")"), java);
    }
}
