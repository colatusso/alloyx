// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.SchemaProvider;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Apex is BLOCK-scoped, but the transpiler's {@code locals} map only ever GREW within a method, so a
 * block-local name leaked past its block. The case-insensitive shadow guard (correct per Apex name
 * resolution) then suppressed a field TOKEN later in the method where the local was no longer in
 * scope, emitting a broken instance read. The fix scopes {@code locals} additions to their block:
 * for-each loop var, classic-for init, if/while/for bodies, try/catch/finally, and VarDecls all snap
 * back when the block ends — including a block-local that temporarily SHADOWED an outer field entry,
 * which restores the field's visibility (not a bare removal).
 */
class BlockScopedLocalsTest {

    private static final Map<String, Map<String, String>> SCHEMA_FIELDS = Map.of(
        "Item__c", Map.of("Id", "Id", "Name", "String", "Code__c", "String"),
        "Opportunity", Map.of("Id", "Id", "Name", "String", "ManualExternalId__c", "String"));

    private static final SchemaProvider SCHEMA = new SchemaProvider() {
        @Override public String fieldType(String s, String f) {
            Map<String, String> fields = lookup(s);
            if (fields == null) return null;
            for (var e : fields.entrySet()) if (e.getKey().equalsIgnoreCase(f)) return e.getValue();
            return null;
        }
        @Override public boolean isDescribed(String s) { return lookup(s) != null; }
        @Override public String canonicalField(String s, String f) {
            Map<String, String> fields = lookup(s);
            if (fields != null) for (var e : fields.entrySet())
                if (e.getKey().equalsIgnoreCase(f)) return e.getKey();
            return f;
        }
        @Override public Map<String, String> fields(String s) { return lookup(s); }

        private Map<String, String> lookup(String s) {
            for (var e : SCHEMA_FIELDS.entrySet()) if (e.getKey().equalsIgnoreCase(s)) return e.getValue();
            return null;
        }
    };

    private String transpile(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), SCHEMA,
            Set.of("Item__c", "Opportunity")).source();
    }

    @Test
    void loopLocalOutOfScope_fieldTokenFiresAfterBlock_instanceInsideBlock() {
        // INSIDE the for-each, `item__c` is a block-local instance -> typed getter (instance read).
        // AFTER the loop the local is OUT OF SCOPE: Item__c.Code__c must be the static field TOKEN.
        String java = transpile("""
            public class OrderSync {
                public static void run(Map<String, Item__c> items) {
                    for (String key : items.keySet()) {
                        Item__c item__c = items.get(key);
                        System.debug(item__c.getName());
                    }
                    upsertAll(items.values(), Item__c.Code__c);
                }
                public static void upsertAll(List<Item__c> l, Schema.SObjectField f) {}
            }
            """);
        assertTrue(java.contains("item__c.getName()"), java);          // instance read inside block
        assertTrue(java.contains("Item__c.Code__c"), java);            // field TOKEN after block
    }

    @Test
    void loopLocalShadowingTypeName_tokenSuppressedInsideButNotAfter() {
        // the corpus shape: a loop-local `opportunity` shadows the type INSIDE the loop, but the
        // field token Opportunity.ManualExternalId__c LATER (local out of scope) must still fire.
        String java = transpile("""
            public class Sync {
                public static void run(List<Opportunity> opps) {
                    for (Opportunity opportunity : opps) {
                        System.debug(opportunity.getName());
                    }
                    tag(Opportunity.ManualExternalId__c);
                }
                public static void tag(Schema.SObjectField f) {}
            }
            """);
        assertTrue(java.contains("opportunity.getName()"), java);             // instance inside loop
        assertTrue(java.contains("Opportunity.ManualExternalId__c"), java);   // token after loop
    }

    @Test
    void ifBlockVarDecl_outOfScopeAfterIf() {
        // a VarDecl inside an if-block shadows the type only within the if; after it, the token fires.
        String java = transpile("""
            public class Sync {
                public static void run(Boolean flag, Item__c seed) {
                    if (flag) {
                        Item__c item__c = seed;
                        System.debug(item__c.getName());
                    }
                    tag(Item__c.Code__c);
                }
                public static void tag(Schema.SObjectField f) {}
            }
            """);
        assertTrue(java.contains("Item__c.Code__c"), java); // token after the if-block
    }

    @Test
    void blockLocalShadowingOuterField_restoresFieldVisibilityAfterBlock() {
        // a FIELD named `item__c` shadows the type method-wide; a block-local of the same name
        // temporarily overrides it, then the field's visibility (and instance semantics) must come
        // back after the block — the field read still routes as an instance getter, not the token.
        String java = transpile("""
            public class Holder {
                private Item__c item__c;
                public String go() {
                    if (true) {
                        Item__c item__c = new Item__c();
                        System.debug(item__c.getName());
                    }
                    return item__c.getName();
                }
            }
            """);
        // the trailing read is the FIELD (instance) again — never the static token Item__c.getName()
        assertTrue(java.contains("return item__c.getName()"), java);
    }
}
