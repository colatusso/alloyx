// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Child-relationship ({@code __r} collection) access on typed sObjects. Apex exposes two
 * {@code __r} shapes on a record: a PARENT reference (singular, derivable from a lookup field
 * the describe DOES carry, e.g. {@code Order__r.Account__r} ← the {@code Account__c} field) and
 * a CHILD collection (plural, e.g. {@code ord.OrderItems__r}) which lives in the parent's
 * childRelationships describe section — NOT in our synced per-object field map. The child case
 * can't be typed to the child sObject (we don't know it), so it types as {@code List<SObject>}
 * and routes through the dynamic {@code getSObjects(name)} runtime path instead of a (nonexistent)
 * typed getter; the existing List&lt;SObject&gt; covariance then lets a typed for-each consume it.
 *
 * <p>All identifiers are invented fixtures (Order__c / OrderItem__c / Account__c) — not a real
 * schema, no hardcoded relationship names in the transpiler.
 */
class ChildRelationshipTest {

    @TempDir
    Path dir;

    /**
     * Fake middleware describing an Order__c parent (with an Account__c lookup, so the parent-ref
     * Account__r is derivable, mirroring {@link alloyx.runtime.SalesforceGateway#describe}'s
     * relationshipName entry) and an OrderItem__c child. The child collection OrderItems__r is NOT
     * a field — it does not appear in any field map, exactly as in a real synced cache.
     */
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
            if (sobjectType.equalsIgnoreCase("Order__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                f.put("Account__c", "Id"); // a lookup FIELD (reference) ...
                f.put("Account__r", "Account"); // ... the gateway also exposes its parent-ref
                return f;
            }
            if (sobjectType.equalsIgnoreCase("OrderItem__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                f.put("Quantity__c", "Integer");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("Account")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                return f;
            }
            return null;
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

    /** Build an Order__c whose OrderItems__r child collection holds two typed child rows. */
    private SObject orderWithItems() {
        alloyx.runtime.List<SObject> items = new alloyx.runtime.List<>();
        items.add(new SObject("OrderItem__c", "Name", "First", "Quantity__c", 3));
        items.add(new SObject("OrderItem__c", "Name", "Second", "Quantity__c", 7));
        SObject ord = new SObject("Order__c", "Name", "O-1");
        ord.put("OrderItems__r", items); // exactly what a SOQL child subquery populates
        return ord;
    }

    @Test
    void forEachOverChildCollection_compilesAndRuns() throws Exception {
        // for (OrderItem__c oi : ord.OrderItems__r) — the child collection is not a field, so it
        // types as List<SObject> and routes through getSObjects("OrderItems__r"); the typed
        // for-each then consumes it via the List<SObject> covariance wrap.
        Path p = probe("OrderService", """
            public class OrderService {
                public static Integer countLines(Order__c ord) {
                    Integer n = 0;
                    for (OrderItem__c oi : ord.OrderItems__r) { n++; }
                    return n;
                }
            }
            """);
        Class<?> svc = Workspace.compile(List.of(p)).load("OrderService");
        // wrap the dynamic record as the generated typed Order__c (the param's declared type)
        Object ord = svc.getClassLoader().loadClass("Order__c")
            .getConstructor(SObject.class).newInstance(orderWithItems());
        Object n = svc.getMethod("countLines",
            svc.getClassLoader().loadClass("Order__c")).invoke(null, ord);
        assertEquals(Integer.valueOf(2), n);
    }

    @Test
    void indexIntoChildCollectionThenReadField_compilesAndRuns() throws Exception {
        // ord.OrderItems__r[0].Name — indexing the child collection yields an SObject, whose
        // field read still emits (dynamic path is fine since the element type is unknown).
        Path p = probe("OrderService", """
            public class OrderService {
                public static String firstLine(Order__c ord) {
                    return (String) ord.OrderItems__r[0].Name;
                }
            }
            """);
        Class<?> svc = Workspace.compile(List.of(p)).load("OrderService");
        Object ord = svc.getClassLoader().loadClass("Order__c")
            .getConstructor(SObject.class).newInstance(orderWithItems());
        Object name = svc.getMethod("firstLine",
            svc.getClassLoader().loadClass("Order__c")).invoke(null, ord);
        assertEquals("First", name);
    }

    @Test
    void sizeAndIsEmptyOnChildCollection_compileAndRun() throws Exception {
        // collection methods on the List<SObject> the child relationship types as
        Path p = probe("OrderService", """
            public class OrderService {
                public static Integer count(Order__c ord) {
                    return ord.OrderItems__r.size();
                }
                public static Boolean none(Order__c ord) {
                    return ord.OrderItems__r.isEmpty();
                }
            }
            """);
        Class<?> svc = Workspace.compile(List.of(p)).load("OrderService");
        Class<?> orderClass = svc.getClassLoader().loadClass("Order__c");
        Object ord = orderClass.getConstructor(SObject.class).newInstance(orderWithItems());
        assertEquals(Integer.valueOf(2), svc.getMethod("count", orderClass).invoke(null, ord));
        assertEquals(Boolean.FALSE, svc.getMethod("none", orderClass).invoke(null, ord));
    }

    @Test
    void parentRefRelationship_keepsTypedRouting() throws Exception {
        // Regression guard: a PARENT-ref __r derivable from a lookup field (Account__r ← Account__c)
        // stays on the typed routing — Account__r is in Order__c's field map typed "Account", so
        // ord.Account__r.Name reads through the generated Account getter chain.
        Path p = probe("OrderService", """
            public class OrderService {
                public static String accountName(Order__c ord) {
                    return ord.Account__r.Name;
                }
            }
            """);
        Class<?> svc = Workspace.compile(List.of(p)).load("OrderService");
        Class<?> orderClass = svc.getClassLoader().loadClass("Order__c");
        SObject acct = new SObject("Account", "Name", "Acme");
        SObject raw = new SObject("Order__c", "Name", "O-1");
        raw.put("Account__r", acct);
        Object ord = orderClass.getConstructor(SObject.class).newInstance(raw);
        assertEquals("Acme", svc.getMethod("accountName", orderClass).invoke(null, ord));
    }

    @Test
    void normalFieldGetterStillGenerated_noRegression() throws Exception {
        // A plain described field still emits a typed getter — the __r routing change must not
        // disturb normal field access. Proven by the emission shape (getName(), not a dynamic get).
        Path p = probe("OrderService", """
            public class OrderService {
                public static String orderName(Order__c ord) {
                    return ord.Name;
                }
            }
            """);
        Workspace.compile(List.of(p)).load("OrderService"); // compiles -> typed getter resolved
        String gen = Files.readString(Workspace.CACHE_DIR.resolve("Order__c.java"));
        assertTrue(gen.contains("getName()"), gen);
    }
}
