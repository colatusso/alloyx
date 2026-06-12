// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * sObject field TOKENS: {@code Item__c.Id} is a STATIC reference on the sObject TYPE that yields a
 * {@code Schema.SObjectField}, used by the selector pattern to build SOQL column lists. The
 * generated typed class carries a {@code public static final SObjectField} per described field;
 * the emitter routes {@code Item__c.Id} to that token and the typer types it as
 * {@code Schema.SObjectField} so a collection literal of field tokens compiles.
 *
 * <p>All identifiers are invented fixtures (Item__c / Name__c / Total__c) — not a real schema,
 * no hardcoded field names in the transpiler.
 */
class SObjectFieldTokenTest {

    @TempDir
    Path dir;

    /** Fake middleware describing the invented Item__c sObject. */
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
            if (sobjectType.equalsIgnoreCase("Item__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name__c", "String");
                f.put("Total__c", "Decimal");
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

    @Test
    void fieldTokenCollectionLiteral_compilesRunsAndStringifiesToApiName() throws Exception {
        // new List<Schema.SObjectField>{ Item__c.Id, Item__c.Name__c } compiles and runs; each
        // element's toString() is the field API name (what selector code joins into SOQL).
        Path p = probe("Selector", """
            public class Selector {
                public static String firstFieldName() {
                    List<Schema.SObjectField> fields =
                        new List<Schema.SObjectField>{ Item__c.Id, Item__c.Name__c, Item__c.Total__c };
                    return String.valueOf(fields.get(1));
                }
                public static Integer fieldCount() {
                    List<Schema.SObjectField> fields =
                        new List<Schema.SObjectField>{ Item__c.Id, Item__c.Name__c, Item__c.Total__c };
                    return fields.size();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Selector");
        assertEquals("Name__c", c.getMethod("firstFieldName").invoke(null)); // element 1 -> field api name
        assertEquals(3, c.getMethod("fieldCount").invoke(null));
    }

    @Test
    void fieldToken_caseInsensitiveApiName() throws Exception {
        // Apex writes Item__c.id (lowercase) too; it must fold to the canonical token Item__c.Id.
        Path p = probe("LowerSelector", """
            public class LowerSelector {
                public static String idTokenName() {
                    Schema.SObjectField f = Item__c.id;
                    return String.valueOf(f);
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("LowerSelector");
        assertEquals("Id", c.getMethod("idTokenName").invoke(null));
    }

    @Test
    void localShadowingTheTypeName_keepsInstanceSemantics() throws Exception {
        // a LOCAL named Item__c shadows the type: Item__c.Name__c must be an INSTANCE field access
        // (the typed setter/getter), NOT the static token (Apex: variables win over types).
        Path p = probe("Shadow", """
            public class Shadow {
                public static String go() {
                    Item__c Item__c = new Item__c();
                    Item__c.Name__c = 'inst';
                    return Item__c.Name__c;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Shadow");
        assertEquals("inst", c.getMethod("go").invoke(null));
    }

    @Test
    void localShadowingTheTypeName_differentCase_keepsInstanceSemantics() throws Exception {
        // a LOCAL named `item__c` (lowercase) shadows the `Item__c` type CASE-INSENSITIVELY (Apex):
        // `item__c.Name__c` must be an INSTANCE field access (typed setter/getter), not the static
        // token — the shadow guard folds case, so a different-cased variable still wins over the type.
        Path p = probe("ShadowLower", """
            public class ShadowLower {
                public static String go() {
                    Item__c item__c = new Item__c();
                    item__c.Name__c = 'inst';
                    return item__c.Name__c;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("ShadowLower");
        assertEquals("inst", c.getMethod("go").invoke(null));
    }

    @Test
    void instanceFieldAccess_stillRoutesThroughGetter_noRegression() throws Exception {
        // a normal instance field read item.Name__c must STILL go through the typed getter, unchanged
        // by the static-token path (the token only fires for a bare TYPE-name target).
        Path p = probe("Reader", """
            public class Reader {
                public static String nameOf(Item__c item) {
                    return item.Name__c;
                }
            }
            """);
        Class<?> reader = Workspace.compile(List.of(p)).load("Reader");
        Object item = reader.getClassLoader().loadClass("Item__c")
            .getDeclaredConstructor().newInstance();
        reader.getClassLoader().loadClass("Item__c")
            .getMethod("setName__c", String.class).invoke(item, "widget");
        assertEquals("widget", reader.getMethod("nameOf",
            reader.getClassLoader().loadClass("Item__c")).invoke(null, item));
    }
}
