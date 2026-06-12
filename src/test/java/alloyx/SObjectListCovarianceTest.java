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
 * Apex List covariance ({@code List<Account>} is-a {@code List<SObject>}) beyond the for-each /
 * direct-SOQL-return sites the {@code .many()} wrap already covered. Java's generics are invariant,
 * so a value typed {@code List<SObject>} (a child-relationship read, a generic query helper) cannot
 * bind a {@code List<Typed>} declared slot without a re-type. These probes pin the SAME {@code
 * Typed.many(...)} wrap at the remaining sites: a VarDecl initializer, a plain assignment, a
 * method-call argument and a constructor argument, where the DECLARED target/param type is a
 * typed-sObject list and the expression types {@code List<SObject>}.
 *
 * <p>All identifiers are invented fixtures (Order__c / OrderItem__c) — not a real schema, nothing
 * hardcoded in the transpiler.
 */
class SObjectListCovarianceTest {

    @TempDir
    Path dir;

    static final class DescribeGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            return new alloyx.runtime.List<>();
        }
        @Override public void insert(alloyx.runtime.List<SObject> records) {}
        @Override public void update(alloyx.runtime.List<SObject> records) {}
        @Override public void delete(alloyx.runtime.List<SObject> records) {}

        @Override
        public Map<String, String> describe(String sobjectType) {
            if (sobjectType.equalsIgnoreCase("Order__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("OrderItem__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                f.put("Quantity__c", "Integer");
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
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    /** An Order__c whose OrderItems__r child collection holds two typed child rows. */
    private SObject orderWithItems() {
        alloyx.runtime.List<SObject> items = new alloyx.runtime.List<>();
        items.add(new SObject("OrderItem__c", "Name", "First", "Quantity__c", 3));
        items.add(new SObject("OrderItem__c", "Name", "Second", "Quantity__c", 7));
        SObject ord = new SObject("Order__c", "Name", "O-1");
        ord.put("OrderItems__r", items);
        return ord;
    }

    private Object invokeWithOrder(String cls, String method) throws Exception {
        Class<?> svc = Workspace.compile(List.of(probe(cls, classBody(cls, method)))).load(cls);
        Class<?> orderClass = svc.getClassLoader().loadClass("Order__c");
        Object ord = orderClass.getConstructor(SObject.class).newInstance(orderWithItems());
        return svc.getMethod("run", orderClass).invoke(null, ord);
    }

    private String classBody(String cls, String method) {
        return "public class " + cls + " {\n" + method + "\n}\n";
    }

    @Test
    void varDeclInitializerRetypesChildRelationship() throws Exception {
        // List<OrderItem__c> lines = ord.OrderItems__r; — child read types List<SObject>, declared
        // slot is List<OrderItem__c>; must wrap as OrderItem__c.many(...) to bind.
        Object n = invokeWithOrder("OrderSvcVar", """
                public static Integer run(Order__c ord) {
                    List<OrderItem__c> lines = ord.OrderItems__r;
                    return lines.size();
                }
            """);
        assertEquals(Integer.valueOf(2), n);
    }

    @Test
    void plainAssignmentRetypesChildRelationship() throws Exception {
        Object n = invokeWithOrder("OrderSvcAssign", """
                public static Integer run(Order__c ord) {
                    List<OrderItem__c> lines = new List<OrderItem__c>();
                    lines = ord.OrderItems__r;
                    return lines.size();
                }
            """);
        assertEquals(Integer.valueOf(2), n);
    }

    @Test
    void methodArgumentRetypesChildRelationship() throws Exception {
        // count(List<OrderItem__c>) called with a List<SObject> child read -> wrap at the arg site.
        Object n = invokeWithOrder("OrderSvcArg", """
                public static Integer run(Order__c ord) {
                    return count(ord.OrderItems__r);
                }
                static Integer count(List<OrderItem__c> items) {
                    return items.size();
                }
            """);
        assertEquals(Integer.valueOf(2), n);
    }

    @Test
    void constructorArgumentRetypesChildRelationship() throws Exception {
        Object n = invokeWithOrder("OrderSvcCtor", """
                public static Integer run(Order__c ord) {
                    Holder h = new Holder(ord.OrderItems__r);
                    return h.count();
                }
                class Holder {
                    List<OrderItem__c> items;
                    Holder(List<OrderItem__c> items) { this.items = items; }
                    Integer count() { return items.size(); }
                }
            """);
        assertEquals(Integer.valueOf(2), n);
    }

    @Test
    void alreadyTypedListNotDoubleWrapped() throws Exception {
        // a List<OrderItem__c> source (no covariance needed) must NOT be wrapped in .many() again.
        ClassDecl d = Parser.parse("""
            public class Pass {
                public static Integer run() {
                    List<OrderItem__c> a = new List<OrderItem__c>();
                    List<OrderItem__c> b = a;
                    return b.size();
                }
            }
            """);
        String src = transpile(d, "Pass");
        assertTrue(!src.contains(".many("), src);
    }

    private String transpile(ClassDecl d, String name) {
        return Transpiler.transpile(d, java.util.Set.of(name),
            new alloyx.runtime.SchemaCache(new DescribeGateway()),
            java.util.Set.of("Order__c", "OrderItem__c"),
            Workspace.memberIndex(List.of(d)), Workspace.memberTypes(List.of(d))).source();
    }
}
