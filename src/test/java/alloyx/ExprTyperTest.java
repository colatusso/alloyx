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
 * Proves the central typer (ExprTyper) feeds the right static type into each emission path:
 * String-field concat stays Java {@code +} (never Decimal.add), a String sObject field routes
 * {@code .split()} through Strings.split, Integer widens to Decimal in the previously-missed
 * contexts (ternary, arithmetic result, call argument, sObject-literal field), and primitive
 * Integer arithmetic stays primitive. Source-shape assertions use a fake SchemaProvider so the
 * transpiler sees a described Account; compile+run cases use the same fake-gateway harness as
 * {@link TypedSObjectTest}.
 */
class ExprTyperTest {

    @TempDir
    Path dir;

    // --- source-shape assertions via a fake schema (Account described, typed) ---

    /** Account fields, the way an org describe would report them. */
    private static final Map<String, String> ACCOUNT_FIELDS = Map.of(
        "Id", "Id", "Name", "String", "Industry", "String",
        "NumberOfEmployees", "Integer", "AnnualRevenue", "Decimal");

    private static final SchemaProvider SCHEMA = new SchemaProvider() {
        @Override
        public String fieldType(String sobjectType, String fieldName) {
            if (!sobjectType.equalsIgnoreCase("Account")) {
                return null;
            }
            String exact = ACCOUNT_FIELDS.get(fieldName);
            if (exact != null) {
                return exact;
            }
            for (var e : ACCOUNT_FIELDS.entrySet()) {
                if (e.getKey().equalsIgnoreCase(fieldName)) {
                    return e.getValue();
                }
            }
            return null;
        }

        @Override
        public boolean isDescribed(String sobjectType) {
            return sobjectType.equalsIgnoreCase("Account");
        }

        @Override
        public String canonicalField(String sobjectType, String fieldName) {
            for (var e : ACCOUNT_FIELDS.entrySet()) {
                if (e.getKey().equalsIgnoreCase(fieldName)) {
                    return e.getKey();
                }
            }
            return fieldName;
        }

        @Override
        public Map<String, String> fields(String sobjectType) {
            return sobjectType.equalsIgnoreCase("Account") ? ACCOUNT_FIELDS : null;
        }
    };

    // transpile a single class against the fake Account schema, with Account typed.
    private String transpileTyped(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), SCHEMA, Set.of("Account")).source();
    }

    // transpile against the fake schema but WITHOUT a generated typed class (described-but-untyped:
    // field access stays the dynamic SObject get/put, yet typing still works off the describe).
    private String transpileDescribedUntyped(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), SCHEMA, Set.of()).source();
    }

    @Test
    void stringFieldConcat_staysJavaPlus_notDecimalAdd() {
        // acct.Name + acct.Industry — both String fields; the bug was the Decimal detector
        // firing on the sObject field and emitting Decimal.add(...). It must stay concatenation.
        String java = transpileTyped("""
            public class C {
                public static String go() {
                    Account acct = new Account(Name = 'a', Industry = 'b');
                    return acct.Name + acct.Industry;
                }
            }
            """);
        assertFalse(java.contains(".add("), "String concat must not become Decimal.add: " + java);
        assertTrue(java.contains("getName() + "), java);
    }

    @Test
    void stringFieldSplit_routesToStringsSplit() {
        // acct.Name.split(',') — target is a String sObject field, so it must route to the
        // Apex-semantics Strings.split (returns a runtime List), not raw Java String.split.
        String java = transpileTyped("""
            public class C {
                public static Object go() {
                    Account acct = new Account(Name = 'a,b,c');
                    return acct.Name.split(',');
                }
            }
            """);
        assertTrue(java.contains("Strings.split("), java);
    }

    @Test
    void stringReturningUserMethod_concatStaysString() {
        // getName() returns String; getName() + 'x' must stay concatenation, never Decimal.add.
        String java = transpileTyped("""
            public class C {
                public String getName() { return 'n'; }
                public String go() { return getName() + 'x'; }
            }
            """);
        assertFalse(java.contains(".add("), java);
        assertTrue(java.contains("getName() + "), java);
    }

    @Test
    void mixedNumberFieldConcat_widensToDecimal() {
        // a Decimal field still drives Decimal arithmetic when there's no String operand.
        String java = transpileTyped("""
            public class C {
                public static Object go() {
                    Account acct = new Account();
                    return acct.AnnualRevenue + acct.NumberOfEmployees;
                }
            }
            """);
        assertTrue(java.contains(".add("), java);
    }

    @Test
    void sObjectLiteralDecimalField_coercesIntegerArgument_typedPath() {
        // new Account(AnnualRevenue = 5): 5 is an Integer literal into a Decimal field -> widen.
        String java = transpileTyped("""
            public class C {
                public static Object go() {
                    return new Account(AnnualRevenue = 5);
                }
            }
            """);
        assertTrue(java.contains("setAnnualRevenue(Decimal.valueOf(5))"), java);
    }

    @Test
    void sObjectLiteralDecimalField_coercesIntegerArgument_untypedPath() {
        // described-but-untyped: dynamic SObject, but the Decimal field value still widens so the
        // stored runtime value is a Decimal (matching Apex), not a bare Integer.
        String java = transpileDescribedUntyped("""
            public class C {
                public static Object go() {
                    return new Account(AnnualRevenue = 5);
                }
            }
            """);
        assertTrue(java.contains("\"AnnualRevenue\", Decimal.valueOf(5)"), java);
    }

    @Test
    void divisionEmitsHalfEven() {
        String java = transpileTyped("""
            public class C {
                public static Decimal go() {
                    Decimal a = 10;
                    Decimal b = 3;
                    return a / b;
                }
            }
            """);
        assertTrue(java.contains("RoundingMode.HALF_EVEN"), java);
        assertFalse(java.contains("HALF_UP"), java);
    }

    @Test
    void plainIntegerArithmetic_staysPrimitive() {
        // regression guard: Integer locals must NOT become Decimal/valueOf-wrapped.
        String java = transpileTyped("""
            public class C {
                public static Integer go() {
                    Integer a = 1;
                    Integer b = a + 1;
                    return b;
                }
            }
            """);
        assertFalse(java.contains("Decimal.valueOf"), java);
        assertFalse(java.contains(".add("), java);
        assertTrue(java.contains("a + 1"), java);
    }

    @Test
    void stringLocalConcat_staysPlain() {
        String java = transpileTyped("""
            public class C {
                public static String go() {
                    String a = 'x';
                    String b = 'y';
                    return a + b;
                }
            }
            """);
        assertFalse(java.contains(".add("), java);
        assertTrue(java.contains("a + b"), java);
    }

    // --- compile + run cases (fake gateway describes Account, exactly like TypedSObjectTest) ---

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
            f.put("Name", "String");
            f.put("Industry", "String");
            f.put("NumberOfEmployees", "Integer");
            f.put("AnnualRevenue", "Decimal");
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
    void ternaryIntoDecimal_coercesAndRuns() throws Exception {
        // Decimal d = cond ? 1 : 2 — both branches Integer literals; the ternary widens to Decimal.
        Path p = probe("Tern", """
            public class Tern {
                public static Decimal go() {
                    Boolean cond = true;
                    Decimal d = cond ? 1 : 2;
                    return d;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Tern").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("1")));
    }

    @Test
    void integerArithmeticIntoDecimal_coercesAndRuns() throws Exception {
        // Integer a; Decimal d = a + 1 — arithmetic result is Integer, widened on assignment.
        Path p = probe("Widen", """
            public class Widen {
                public static Decimal go() {
                    Integer a = 4;
                    Decimal d = a + 1;
                    return d;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Widen").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("5")));
    }

    @Test
    void decimalParamCallArgument_coercesAndRuns() throws Exception {
        // void pay(Decimal amount) called as pay(5): the Integer literal widens to the Decimal param.
        Path p = probe("Pay", """
            public class Pay {
                private Decimal total;
                public Decimal pay(Decimal amount) { this.total = amount; return this.total; }
                public static Decimal go() {
                    Pay pay = new Pay();
                    return pay.pay(5);
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Pay").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) v).compareTo(alloyx.runtime.Decimal.valueOf("5")));
    }

    @Test
    void stringFieldConcat_compilesAndRuns() throws Exception {
        // end-to-end: String-field concat must produce a String, not attempt Decimal arithmetic.
        Path p = probe("Concat", """
            public class Concat {
                public static String go() {
                    Account a = new Account(Name = 'Foo', Industry = 'Bar');
                    return a.Name + a.Industry;
                }
            }
            """);
        Object v = Workspace.compile(List.of(p)).load("Concat").getMethod("go").invoke(null);
        assertEquals("FooBar", v);
    }
}
