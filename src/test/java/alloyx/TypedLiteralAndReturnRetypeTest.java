// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two regression shapes from corpus validation of the typed-sObject fixes.
 *
 * <p><b>Shape A</b> — a ternary {@code <expr>.Id : null} inside a TYPED sObject literal argument. When
 * the relationship map/value is an ENCLOSING-INSTANCE field, the typed-literal anon-block rewrite
 * qualifies the chain to {@code <Cls>.this.distributors.get(...).Id}; the trailing sObject field hop
 * must still resolve through the typed getter ({@code .getId()}), not degrade to a raw {@code .Id}
 * (which fails to compile) or a {@code Schema.SObjectField} token. The static-param variant (no
 * qualification) must keep working too.
 *
 * <p><b>Shape B</b> — a lowercase return type ({@code list<contact>}) with a direct SOQL return. The
 * runtime query yields {@code List<SObject>}; the return path must re-type it to the declared element
 * via {@code Contact.many(...)}, matching the for-each wrap — and the collection/sObject base names
 * must fold case-insensitively (Apex), so {@code list<contact>} re-types exactly like {@code
 * List<Contact>}. A bare {@code id} param type folds to String, like {@code Id}.
 *
 * <p>All identifiers are invented fixtures (Asset__c / Item__c / Distributor__c / ContactDao) — not a
 * real schema, no hardcoded names in the transpiler.
 */
class TypedLiteralAndReturnRetypeTest {

    @TempDir
    Path dir;

    static final class DescribeGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            alloyx.runtime.List<SObject> out = new alloyx.runtime.List<>();
            out.add(new SObject("Contact", "Id", "003", "Name", "Bob"));
            return out;
        }
        @Override public void insert(alloyx.runtime.List<SObject> records) {}
        @Override public void update(alloyx.runtime.List<SObject> records) {}
        @Override public void delete(alloyx.runtime.List<SObject> records) {}
        @Override
        public Set<String> globalSObjects() {
            // a real org's global describe gives canonical casing, so a lowercase `contact` in the
            // source folds to the org's `Contact` (the generated class links against that one spelling).
            return Set.of("Account", "Item__c", "Asset__c", "Contact");
        }
        @Override
        public Map<String, String> describe(String sobjectType) {
            if (sobjectType.equalsIgnoreCase("Account")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("Item__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Code__c", "String");
                f.put("Name", "String");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("Asset__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Code__c", "String");
                f.put("Distributor__c", "String");
                f.put("Name", "String");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("Contact")) {
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
    void shapeA_staticParams() throws Exception {
        Path p = probe("AssetTransformer", """
            public class AssetTransformer {
                public static Asset__c convert(Item__c src, Map<String, Account> distributors) {
                    return new Asset__c(
                        Code__c = src.Code__c,
                        Distributor__c = distributors != null && distributors.get(src.Code__c) != null
                            ? distributors.get(src.Code__c).Id
                            : null,
                        Name = src.Name
                    );
                }
            }
            """);
        runConvertWithParam(Workspace.compile(List.of(p)));
    }

    @Test
    void shapeA_fieldRooted() throws Exception {
        // the FIELD variant: distributors is an instance field, so qualifyEnclosing rewrites the
        // chain to <Cls>.this.distributors.get(...).Id inside the anon init block.
        Path p = probe("AssetTransformer", """
            public class AssetTransformer {
                Map<String, Account> distributors;
                public Asset__c convert(Item__c src) {
                    return new Asset__c(
                        Code__c = src.Code__c,
                        Distributor__c = distributors != null && distributors.get(src.Code__c) != null
                            ? distributors.get(src.Code__c).Id
                            : null,
                        Name = src.Name
                    );
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("AssetTransformer");
        Class<?> item = k.getClassLoader().loadClass("Item__c");
        Class<?> account = k.getClassLoader().loadClass("Account");
        Class<?> asset = k.getClassLoader().loadClass("Asset__c");

        Object src = item.getDeclaredConstructor().newInstance();
        item.getMethod("setCode__c", String.class).invoke(src, "C1");
        Object acct = account.getDeclaredConstructor().newInstance();
        account.getMethod("setId", String.class).invoke(acct, "a-9");

        alloyx.runtime.Map<String, Object> map = new alloyx.runtime.Map<>();
        map.put("C1", acct);
        Object inst = k.getDeclaredConstructor().newInstance();
        var fld = k.getDeclaredField("distributors");
        fld.setAccessible(true);
        fld.set(inst, map);

        Object row = k.getMethod("convert", item).invoke(inst, src);
        assertEquals("a-9", asset.getMethod("getDistributor__c").invoke(row));
    }

    private void runConvertWithParam(Workspace.Compiled c) throws Exception {
        Class<?> k = c.load("AssetTransformer");
        Class<?> item = k.getClassLoader().loadClass("Item__c");
        Class<?> account = k.getClassLoader().loadClass("Account");
        Class<?> asset = k.getClassLoader().loadClass("Asset__c");

        Object src = item.getDeclaredConstructor().newInstance();
        item.getMethod("setCode__c", String.class).invoke(src, "C1");

        Object acct = account.getDeclaredConstructor().newInstance();
        account.getMethod("setId", String.class).invoke(acct, "a-9");

        alloyx.runtime.Map<String, Object> map = new alloyx.runtime.Map<>();
        map.put("C1", acct);

        Object row = k.getMethod("convert", item, alloyx.runtime.Map.class).invoke(null, src, map);
        assertEquals("a-9", asset.getMethod("getDistributor__c").invoke(row));

        alloyx.runtime.Map<String, Object> empty = new alloyx.runtime.Map<>();
        Object row2 = k.getMethod("convert", item, alloyx.runtime.Map.class).invoke(null, src, empty);
        assertNull(asset.getMethod("getDistributor__c").invoke(row2));
    }

    @Test
    void shapeB_lowercaseReturnDirectSoql() throws Exception {
        Path p = probe("ContactDao", """
            public class ContactDao {
                public static list<contact> byUser(id userId) {
                    return [SELECT Id, Name FROM Contact WHERE OwnerId = :userId];
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("ContactDao");
        Object result = k.getMethod("byUser", String.class).invoke(null, "u-1");
        alloyx.runtime.List<?> list = (alloyx.runtime.List<?>) result;
        assertEquals(1, list.size());
    }

    @Test
    void shapeA_sourceShape_fieldRootedGetterNotToken() throws Exception {
        // source-shape guard for the regression: the field-rooted chain must end in the typed getter
        // getId(), never the raw `.Id` (a degraded field read / Schema.SObjectField token).
        ClassDecl d = Parser.parse("""
            public class AssetTransformer {
                Map<String, Account> distributors;
                public Asset__c convert(Item__c src) {
                    return new Asset__c(
                        Distributor__c = distributors.get(src.Code__c).Id
                    );
                }
            }
            """);
        String src = transpileWithSchema(d);
        assertTrue(src.contains(".get(src.getCode__c()).getId()"), src);
        assertTrue(src.contains("AssetTransformer.this.distributors"), src);
    }

    /** Transpile a single class against the live gateway-backed schema (typed Account/Item__c/Asset__c). */
    private String transpileWithSchema(ClassDecl d) {
        List<ClassDecl> all = List.of(d);
        return Transpiler.transpile(d, Set.of("AssetTransformer"),
            new alloyx.runtime.SchemaCache(new DescribeGateway()),
            Set.of("Account", "Item__c", "Asset__c"),
            Workspace.memberIndex(all), Workspace.memberTypes(all)).source();
    }
}
