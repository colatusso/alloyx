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
 * A TYPED sObject literal ({@code new Item__c(Id = this.id, Name = name)}) lowers to a double-brace
 * initializer — {@code new Item__c(){{ setId(this.id); setName(name); }}}. Inside that anonymous
 * subclass init block, a bare {@code this} rebinds to the anonymous instance and a bare name resolves
 * first against the constructed typed class's members (which now include {@code public static final
 * SObjectField <FieldApiName>} statics). So an arg value referencing the ENCLOSING class — {@code
 * this.id} (the wrapper's field, absent on the typed class) or a bare {@code name} that collides with
 * a field token — silently breaks: {@code this.id} fails to compile; a bare colliding name rebinds to
 * the token. Arg values that touch the enclosing instance must emit the qualified enclosing form
 * ({@code <EnclosingClass>.this.<member>}); locals and params must stay bare.
 *
 * <p>All identifiers are invented fixtures (Item__c / Quantity__c / OrderItemOut / ...) — not a real
 * schema, no hardcoded field names in the transpiler.
 */
class TypedLiteralThisBindingTest {

    @TempDir
    Path dir;

    /** Fake middleware describing an Item__c with Id / Name / Quantity__c. */
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
                f.put("Name", "String");
                f.put("Quantity__c", "Decimal");
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

    /**
     * Set an instance field reflectively. Apex {@code public} fields lower to package-private Java
     * fields (no modifier), so {@link Class#getField} can't see them; the declared-field handle with
     * {@code setAccessible} sets the wrapper's state the way an Apex caller would.
     */
    private static void setField(Class<?> k, Object inst, String name, Object value) throws Exception {
        var f = k.getDeclaredField(name);
        f.setAccessible(true);
        f.set(inst, value);
    }

    @Test
    void thisFieldInTypedLiteral_compilesAndRoundTrips() throws Exception {
        // The corpus shape: a wrapper DTO converts itself to a typed sObject, mixing `this.id` and
        // bare field reads. `this.id` must NOT resolve against the anon Item__c subclass (it has no
        // `id`) — it must read the wrapper's field via Item__c-independent qualified this.
        Path p = probe("OrderItemOut", """
            public class OrderItemOut {
                public String id;
                public String name;
                public Decimal quantity;
                public Item__c toSObject() {
                    return new Item__c(
                        Id = this.id,
                        Name = name,
                        Quantity__c = quantity
                    );
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("OrderItemOut");
        Object inst = k.getDeclaredConstructor().newInstance();
        setField(k, inst, "id", "i-1");
        setField(k, inst, "name", "Widget");
        setField(k, inst, "quantity", alloyx.runtime.Decimal.valueOf("4"));

        SObject row = (SObject) k.getMethod("toSObject").invoke(inst);
        assertEquals("i-1", row.get("Id"));
        assertEquals("Widget", row.get("Name"));
        assertEquals(alloyx.runtime.Decimal.valueOf("4"), row.get("Quantity__c"));
    }

    @Test
    void bareFieldRefInTypedLiteral_runs() throws Exception {
        // `Name = name` where `name` is the wrapper's field, used bare. The bare ref must read the
        // wrapper field, not the anon subclass's inherited `Name` token static.
        Path p = probe("NameOut", """
            public class NameOut {
                public String name;
                public Item__c build() {
                    return new Item__c(Name = name);
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("NameOut");
        Object inst = k.getDeclaredConstructor().newInstance();
        setField(k, inst, "name", "Bare");
        SObject row = (SObject) k.getMethod("build").invoke(inst);
        assertEquals("Bare", row.get("Name"));
    }

    @Test
    void wrapperFieldNamedLikeTokenWinsOverToken() throws Exception {
        // A wrapper field named EXACTLY like a std field token (`Name`) — legal in Apex — used bare
        // in the literal must read the WRAPPER's field, not the generated `SObjectField Name` static.
        Path p = probe("TokenClash", """
            public class TokenClash {
                public String Name;
                public Item__c build() {
                    return new Item__c(Name = Name);
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("TokenClash");
        Object inst = k.getDeclaredConstructor().newInstance();
        setField(k, inst, "Name", "FromWrapper");
        SObject row = (SObject) k.getMethod("build").invoke(inst);
        assertEquals("FromWrapper", row.get("Name"));
    }

    @Test
    void thisFieldInTypedLiteral_insideInnerClassMethod() throws Exception {
        // The literal lives in an INNER class method using `this.<field>` of the INNER class. The
        // qualified-this must name the inner (emitted as a static nested `class Inner`), so
        // `Inner.this.id` resolves regardless of nesting under the anon block. Driven through a static
        // method on Outer (Apex inner classes are package-private static nested in Java — black-box).
        Path p = probe("Outer", """
            public class Outer {
                public class Inner {
                    public String id;
                    public String label;
                    public Item__c toSObject() {
                        return new Item__c(Id = this.id, Name = label);
                    }
                }
                public static Item__c build(String id, String label) {
                    Inner in = new Inner();
                    in.id = id;
                    in.label = label;
                    return in.toSObject();
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> outer = c.load("Outer");
        SObject row = (SObject) outer.getMethod("build", String.class, String.class)
            .invoke(null, "in-1", "InnerName");
        assertEquals("in-1", row.get("Id"));
        assertEquals("InnerName", row.get("Name"));
    }

    @Test
    void localVarArgStaysBare_andWorks() throws Exception {
        // A LOCAL feeding an arg value must stay bare (Java locals can't be shadowed by inherited
        // members; qualifying them would be wrong). No over-qualification.
        Path p = probe("LocalOut", """
            public class LocalOut {
                public static Item__c build() {
                    String n = 'Local';
                    return new Item__c(Name = n);
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("LocalOut");
        SObject row = (SObject) k.getMethod("build").invoke(null);
        assertEquals("Local", row.get("Name"));
    }

    @Test
    void nestedTypedLiteralWithThisField_runs() throws Exception {
        // A typed literal as an arg value of ANOTHER typed literal, both touching `this`. The
        // qualified rewrite is depth-independent and must apply at every literal's args without
        // double-qualifying.
        Path p = probe("Nested", """
            public class Nested {
                public String id;
                public String childId;
                public Item__c toSObject() {
                    return new Item__c(
                        Id = this.id,
                        Quantity__c = new Item__c(Id = this.childId).getQuantity__c()
                    );
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("Nested");
        Object inst = k.getDeclaredConstructor().newInstance();
        setField(k, inst, "id", "parent");
        setField(k, inst, "childId", "child");
        SObject row = (SObject) k.getMethod("toSObject").invoke(inst);
        assertEquals("parent", row.get("Id"));
    }

    @Test
    void thisMethodCallInTypedLiteral_runs() throws Exception {
        // A `this.m()` arg value (bare method call too) must qualify so it doesn't bind to the anon
        // subclass — the typed Item__c has no user method `computeName`.
        Path p = probe("MethodOut", """
            public class MethodOut {
                public String computeName() { return 'computed'; }
                public Item__c build() {
                    return new Item__c(Name = this.computeName());
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("MethodOut");
        Object inst = k.getDeclaredConstructor().newInstance();
        SObject row = (SObject) k.getMethod("build").invoke(inst);
        assertEquals("computed", row.get("Name"));
    }

    @Test
    void typedLiteralInVarDeclInit_withThisField_runs() throws Exception {
        // Statement-position variant: a typed literal in a VarDecl init flows through the same
        // emission and must qualify `this.<field>` too.
        Path p = probe("VarDeclOut", """
            public class VarDeclOut {
                public String id;
                public Item__c toSObject() {
                    Item__c row = new Item__c(Id = this.id);
                    return row;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(p));
        Class<?> k = c.load("VarDeclOut");
        Object inst = k.getDeclaredConstructor().newInstance();
        setField(k, inst, "id", "vd-1");
        SObject row = (SObject) k.getMethod("toSObject").invoke(inst);
        assertEquals("vd-1", row.get("Id"));
    }
}
