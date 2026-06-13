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
 * RC4: {@code List<Item__c> items = (List<Item__c>) JSON.deserialize(payload, List<Item__c>.class);}
 * {@code JSON.deserialize} returns {@code Object}, but the cast-to-{@code List<Typed>} emission
 * routed it straight through the covariance {@code Typed.many(...)} wrap, which takes a
 * {@code List<SObject>} — so javac saw {@code Object cannot be converted to List<SObject>}.
 *
 * <p>The fix: a cast FROM an Object-typed source TO a typed-sObject collection casts the source down
 * to {@code List<SObject>} before the {@code .many()} re-type (legal Java; unchecked already
 * suppressed), and the runtime {@code JSON.deserialize(json, type)} materializes the JSON model into
 * generic {@code SObject} rows so the re-type produces real typed rows at runtime. A covariance cast
 * {@code (List<Item__c>) someListSObject} from a {@code List<SObject>}-typed source still routes
 * through {@code .many()} directly (unchanged). All identifiers are invented fixtures.
 */
class DeserializeCastTest {

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
            if (sobjectType.equalsIgnoreCase("Item__c")) {
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

    @Test
    void deserializeIntoTypedListCompilesAndRunsTyped() throws Exception {
        // A JSON array of two objects -> List<Item__c>; element member access stays typed (getName()).
        Class<?> svc = Workspace.compile(List.of(probe("ItemLoader", """
            public class ItemLoader {
                public static String firstName(String payload) {
                    List<Item__c> items = (List<Item__c>) JSON.deserialize(payload, List<Item__c>.class);
                    return items[0].Name;
                }
            }
            """))).load("ItemLoader");
        String payload = "[{\"Name\":\"Alpha\",\"Quantity__c\":3},{\"Name\":\"Beta\",\"Quantity__c\":7}]";
        Object out = svc.getMethod("firstName", String.class).invoke(null, payload);
        assertEquals("Alpha", out);
    }

    @Test
    void deserializeIntoTypedListSizeIsCorrect() throws Exception {
        // The materialized list has the right cardinality (two rows) and a typed numeric getter works.
        Class<?> svc = Workspace.compile(List.of(probe("ItemCount", """
            public class ItemCount {
                public static Integer total(String payload) {
                    List<Item__c> items = (List<Item__c>) JSON.deserialize(payload, List<Item__c>.class);
                    Integer sum = 0;
                    for (Item__c it : items) sum += it.Quantity__c;
                    return sum;
                }
            }
            """))).load("ItemCount");
        String payload = "[{\"Name\":\"Alpha\",\"Quantity__c\":3},{\"Name\":\"Beta\",\"Quantity__c\":7}]";
        Object out = svc.getMethod("total", String.class).invoke(null, payload);
        assertEquals(Integer.valueOf(10), out);
    }

    @Test
    void covarianceCastStillRoutesThroughMany() throws Exception {
        // A cast whose SOURCE already types as List<SObject> (a child-relationship read) is the
        // covariance case and must keep the direct .many() routing (no (List<SObject>)(Object) hop).
        ClassDecl d = Parser.parse("""
            public class Cov {
                public static Integer run(Order__c ord) {
                    List<Item__c> items = (List<Item__c>) ord.Items__r;
                    return items.size();
                }
            }
            """);
        String src = Transpiler.transpile(d, java.util.Set.of("Cov"),
            new alloyx.runtime.SchemaCache(new DescribeGateway()),
            java.util.Set.of("Item__c", "Order__c"),
            Workspace.memberIndex(List.of(d)), Workspace.memberTypes(List.of(d))).source();
        assertTrue(src.contains("Item__c.many("), src);
        // the covariance source is already a List<SObject>, so no Object down-cast hop is inserted
        assertTrue(!src.contains("(Object) ord.Items__r"), src);
    }
}
