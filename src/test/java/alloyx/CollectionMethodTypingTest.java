// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.SchemaProvider;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fix A: builtin collection-method results (map.get(id).Field, list.get(0).Field, the values()
 * view) are now typed off the collection's generics, so the sObject field/method hop on the
 * result routes through the typed getter and the generated Java compiles AND runs. A raw List
 * (no generics) stays unchanged, and size() stays an Integer so arithmetic on it doesn't
 * mis-route. The end-to-end cases reuse the same fake-gateway harness as {@link TypedSObjectTest},
 * describing a custom sObject {@code Inv__c} with a Decimal {@code Total__c}.
 */
class CollectionMethodTypingTest {

    @TempDir
    Path dir;

    // --- source-shape assertions (fast, no compile): a fake schema describing Inv__c, typed ---

    private static final Map<String, String> INV_FIELDS = Map.of("Id", "Id", "Total__c", "Decimal");

    private static final SchemaProvider SCHEMA = new SchemaProvider() {
        @Override
        public String fieldType(String sobjectType, String fieldName) {
            if (!sobjectType.equalsIgnoreCase("Inv__c")) {
                return null;
            }
            for (var e : INV_FIELDS.entrySet()) {
                if (e.getKey().equalsIgnoreCase(fieldName)) {
                    return e.getValue();
                }
            }
            return null;
        }

        @Override
        public boolean isDescribed(String sobjectType) {
            return sobjectType.equalsIgnoreCase("Inv__c");
        }

        @Override
        public String canonicalField(String sobjectType, String fieldName) {
            for (var e : INV_FIELDS.entrySet()) {
                if (e.getKey().equalsIgnoreCase(fieldName)) {
                    return e.getKey();
                }
            }
            return fieldName;
        }

        @Override
        public Map<String, String> fields(String sobjectType) {
            return sobjectType.equalsIgnoreCase("Inv__c") ? INV_FIELDS : null;
        }
    };

    private String transpileTyped(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), SCHEMA, Set.of("Inv__c")).source();
    }

    @Test
    void mapGetFieldRead_routesThroughTypedGetter() {
        // m.get(id).Total__c — get() returns the Map value V (Inv__c), so the field hop must
        // become the typed getter, not a raw `.Total__c` access (which would not compile).
        String java = transpileTyped("""
            public class C {
                public static Object go(Map<Id, Inv__c> m, Id key) {
                    return m.get(key).Total__c;
                }
            }
            """);
        assertTrue(java.contains("m.get(key).getTotal__c()"), java);
    }

    @Test
    void listGetFieldRead_routesThroughTypedGetter() {
        // l.get(0).Total__c — List get() returns the element T (Inv__c).
        String java = transpileTyped("""
            public class C {
                public static Object go(List<Inv__c> l) {
                    return l.get(0).Total__c;
                }
            }
            """);
        assertTrue(java.contains("l.get(0).getTotal__c()"), java);
    }

    @Test
    void rawListGet_staysUntouched_noNewTyping() {
        // a RAW List (no generics) must not gain any new typing: get() result is unknown, so the
        // field hop falls through to the dynamic path exactly as today (a `.get("...")` access).
        String java = transpileTyped("""
            public class C {
                public static Object go(List records) {
                    return records.get(0).Total__c;
                }
            }
            """);
        assertFalse(java.contains("getTotal__c()"), java);
        assertTrue(java.contains("records.get(0).Total__c"), java); // unchanged, raw access

    }

    @Test
    void mapSizeStaysInteger_arithmeticNotMisrouted() {
        // size() must type as Integer so `m.size() + 1` stays primitive arithmetic, never the
        // Decimal/concat path a wrong type would trigger.
        String java = transpileTyped("""
            public class C {
                public static Integer go(Map<Id, Inv__c> m) {
                    return m.size() + 1;
                }
            }
            """);
        assertFalse(java.contains(".add("), java);
        assertFalse(java.contains("Decimal.valueOf"), java);
        assertTrue(java.contains("m.size() + 1"), java);
    }

    // --- end-to-end compile + run (fake gateway describes Inv__c, like TypedSObjectTest) ---

    static final class DescribeGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            return new alloyx.runtime.List<>();
        }

        @Override
        public void insert(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void update(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void delete(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public Map<String, String> describe(String sobjectType) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("Id", "Id");
            f.put("Total__c", "Decimal");
            return f;
        }
    }

    @BeforeEach
    void connect() throws Exception {
        cleanSchemaCache();
        Database.setGateway(new DescribeGateway());
    }

    @AfterEach
    void disconnect() throws Exception {
        Database.setGateway(new UnconnectedGateway());
        cleanSchemaCache();
    }

    private void cleanSchemaCache() throws Exception {
        Path schema = Workspace.CACHE_DIR.resolve("schema");
        if (Files.isDirectory(schema)) {
            try (var w = Files.walk(schema)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void mapGetFieldReadAndWrite_compilesAndRuns() throws Exception {
        // m.get(key).Total__c read AND write, both through the typed getter/setter on the Map value.
        Path p = probe("MapGet", """
            public class MapGet {
                public static Decimal go() {
                    Map<Id, Inv__c> m = new Map<Id, Inv__c>();
                    Id key = '500';
                    Inv__c inv = new Inv__c(Total__c = 100);
                    m.put(key, inv);
                    m.get(key).Total__c = m.get(key).Total__c + 5;
                    return m.get(key).Total__c;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("MapGet").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("105")));
    }

    @Test
    void listGetField_compilesAndRuns() throws Exception {
        // l.get(0).Total__c read, the element typed as Inv__c off List<Inv__c>.
        Path p = probe("ListGet", """
            public class ListGet {
                public static Decimal go() {
                    List<Inv__c> l = new List<Inv__c>();
                    l.add(new Inv__c(Total__c = 42));
                    return l.get(0).Total__c;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("ListGet").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("42")));
    }

    @Test
    void mapFromList_keysById_compilesAndRuns() throws Exception {
        // new Map<Id, Inv__c>(records) — the Id-keying list constructor. The list is built from
        // typed records; the constructor keys each by its Id and the typed getter reads back off
        // the Map value. Proves the SObject-bound constructor binds and runs end-to-end.
        Path p = probe("ById", """
            public class ById {
                public static Decimal go() {
                    List<Inv__c> records = new List<Inv__c>();
                    records.add(new Inv__c(Id = 'a01', Total__c = 7));
                    records.add(new Inv__c(Id = 'a02', Total__c = 11));
                    Map<Id, Inv__c> byId = new Map<Id, Inv__c>(records);
                    return byId.get('a02').Total__c;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("ById").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("11")));
    }

    @Test
    void forEachOverValues_compilesAndRuns() throws Exception {
        // for (Inv__c x : m.values()) { x.Total__c } — values() is a List<Inv__c> view; the loop
        // body reads the typed getter. Sums the totals to prove the iteration ran end-to-end.
        Path p = probe("Sum", """
            public class Sum {
                public static Decimal go() {
                    Map<Id, Inv__c> m = new Map<Id, Inv__c>();
                    m.put('1', new Inv__c(Total__c = 10));
                    m.put('2', new Inv__c(Total__c = 20));
                    Decimal total = 0;
                    for (Inv__c x : m.values()) {
                        total = total + x.Total__c;
                    }
                    return total;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Sum").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("30")));
    }

    @Test
    void valuesAssignedToList_compilesAndRuns() throws Exception {
        // List<Inv__c> l = m.values(); — values() is typed List<Inv__c>, so the assignment to an
        // alloyx List<Inv__c> must compile (the override returns an alloyx List, not a HashMap view)
        // and l.size() must reflect every value.
        Path p = probe("Vals", """
            public class Vals {
                public static Integer go() {
                    Map<Id, Inv__c> m = new Map<Id, Inv__c>();
                    m.put('1', new Inv__c(Total__c = 10));
                    m.put('2', new Inv__c(Total__c = 20));
                    List<Inv__c> l = m.values();
                    return l.size();
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Vals").getMethod("go").invoke(null);
        assertEquals(2, ((Number) v).intValue());
    }

    @Test
    void keySetAssignedToSet_compilesAndRuns() throws Exception {
        // Set<Id> ks = m.keySet(); — keySet() is typed Set<Id>, so the assignment to an alloyx
        // Set<Id> must compile (the override returns an alloyx Set, not a HashMap view) and
        // ks.size() must reflect every key.
        Path p = probe("Keys", """
            public class Keys {
                public static Integer go() {
                    Map<Id, Inv__c> m = new Map<Id, Inv__c>();
                    m.put('1', new Inv__c(Total__c = 10));
                    m.put('2', new Inv__c(Total__c = 20));
                    Set<Id> ks = m.keySet();
                    return ks.size();
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Keys").getMethod("go").invoke(null);
        assertEquals(2, ((Number) v).intValue());
    }
}
