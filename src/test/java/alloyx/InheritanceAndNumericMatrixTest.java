// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.Decimal;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.math.BigDecimal;
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
 * Two fix families, end-to-end.
 *
 * <p><b>Inheritance:</b> a member lookup walks the {@code extends} chain — a param/field whose
 * member is declared on a SUPERCLASS resolves to its declared type, so an inherited sObject field
 * read routes to a typed getter and an inherited method's return type is known. The chain is stored
 * by {@link Transpiler#populateMemberTypes} under the reserved {@code (extends)} key and walked by
 * {@link ExprTyper#memberType} with a cycle guard. A malformed two-class cycle must still terminate.
 *
 * <p><b>Numeric matrix (Decimal/Integer):</b> a constructor Decimal param coerces an Integer
 * argument; a Decimal relational comparison ({@code dec > 5}, {@code 5 <= dec}) routes through
 * compareTo; a mixed/all-Integer ternary in a Decimal context widens per branch; a Decimal field
 * initializer {@code = 0} widens; a {@code (Integer) dec} narrowing cast becomes intValue().
 *
 * <p>All identifiers are invented fixtures (Order/Ledger/Vehicle/Sedan/...), not from any schema.
 */
class InheritanceAndNumericMatrixTest {

    @TempDir
    Path dir;

    /** Fake middleware that describes the invented Receipt__c sObject (for the inherited-field test). */
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
            if (!sobjectType.equalsIgnoreCase("Receipt__c")) {
                return null;
            }
            Map<String, String> f = new LinkedHashMap<>();
            f.put("Id", "Id");
            f.put("Name", "String");
            f.put("Memo__c", "String");
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

    // --- Fix 1: inheritance chain ---

    @Test
    void inheritedSObjectField_twoLevelChain_usesTypedGetterEndToEnd() throws Exception {
        // Base.receipt is a Receipt__c (typed sObject) field; Middle adds nothing; Leaf (a param of
        // type Leaf extends Middle extends Base) reads leaf.receipt.Memo__c. The member lookup must
        // climb two levels to find `receipt`, so the final hop emits the typed getter, not raw access.
        Path base = probe("Base", """
            public class Base {
                public Receipt__c receipt;
            }
            """);
        Path middle = probe("Middle", """
            public class Middle extends Base {
            }
            """);
        Path leaf = probe("Leaf", """
            public class Leaf extends Middle {
            }
            """);
        Path reader = probe("Reader", """
            public class Reader {
                public static String memoOf(Leaf leaf) {
                    leaf.receipt = new Receipt__c(Memo__c = 'note');
                    return leaf.receipt.Memo__c;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(base, middle, leaf, reader));
        Class<?> leafCls = c.load("Leaf");
        Object leafInst = leafCls.getDeclaredConstructor().newInstance();
        Object result = c.load("Reader").getMethod("memoOf", leafCls).invoke(null, leafInst);
        assertEquals("note", result);
    }

    @Test
    void inheritedMethodReturnType_resolvesThroughChain() throws Exception {
        // Repo.discount() returns Decimal, declared on the SUPERCLASS. Buyer calls
        // shop.discount() + 1 on a SpecialRepo (extends Repo). With the return type resolved
        // cross-chain as Decimal, '+' becomes Decimal.add — not a (mis)routed primitive +.
        Path repo = probe("Repo", """
            public class Repo {
                public Decimal discount() { return 10; }
            }
            """);
        Path special = probe("SpecialRepo", """
            public class SpecialRepo extends Repo {
            }
            """);
        Path buyer = probe("Buyer", """
            public class Buyer {
                public static Decimal total(SpecialRepo shop) {
                    return shop.discount() + 1;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(repo, special, buyer));
        Class<?> specialCls = c.load("SpecialRepo");
        Object shop = specialCls.getDeclaredConstructor().newInstance();
        Object result = c.load("Buyer").getMethod("total", specialCls).invoke(null, shop);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("11")));
    }

    @Test
    void thisRootedInheritedField_resolvesThroughChain() throws Exception {
        // Child (extends Parent) reads this.tag — `tag` is declared on Parent. The this-rooted
        // lookup misses the current body's fields and must fall through the inherited-member walk.
        Path parent = probe("Parent", """
            public class Parent {
                public String tag;
            }
            """);
        Path child = probe("Child", """
            public class Child extends Parent {
                public String reveal() {
                    this.tag = 'x';
                    return this.tag;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(parent, child));
        Class<?> childCls = c.load("Child");
        Object inst = childCls.getDeclaredConstructor().newInstance();
        Object result = childCls.getMethod("reveal").invoke(inst);
        assertEquals("x", result);
    }

    @Test
    void bareNameInheritedField_sObjectHopUsesTypedGetter() throws Exception {
        // Worker (extends Base) reads `slip.Memo__c` where `slip` is Base's field referenced
        // WITHOUT `this.` — only the current body's own fields live in locals, so a bare-name
        // read of an inherited field must also resolve through the extends walk, or the sObject
        // hop emits raw field access instead of the typed getter.
        Path base = probe("Base", """
            public class Base {
                public Receipt__c slip;
            }
            """);
        Path worker = probe("Worker", """
            public class Worker extends Base {
                public String memo() {
                    slip = new Receipt__c(Memo__c = 'bare');
                    return slip.Memo__c;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(base, worker));
        Class<?> w = c.load("Worker");
        Object inst = w.getDeclaredConstructor().newInstance();
        assertEquals("bare", w.getMethod("memo").invoke(inst));
    }

    @Test
    void inheritanceCycleInInput_doesNotHang() throws Exception {
        // Malformed input: two classes extending each other. The chain walk's cycle guard must
        // make the transpile terminate — any non-hang outcome is acceptable (here it compiles).
        // a member access for a name declared on NEITHER class forces the lookup to walk
        // A -> B -> A ... the cycle guard must stop it (or the walk spins forever).
        Path loopA = probe("LoopA", """
            public class LoopA extends LoopB {
                public Object hello(LoopA other) { return other.missingMember; }
            }
            """);
        Path loopB = probe("LoopB", """
            public class LoopB extends LoopA {
                public String world() { return 'b'; }
            }
            """);
        // The bug being guarded is an INFINITE LOOP in the typer's chain walk; reaching any
        // assertion at all proves termination. javac will reject the Java cyclic inheritance,
        // so we assert the transpiler (not the compile) terminates by transpiling directly.
        String aSrc = Transpiler.transpile(
            Parser.parse(Files.readString(loopA)),
            Set.of("LoopA", "LoopB"), (o, f) -> null, Set.of(),
            Workspace.memberIndex(List.of(
                Parser.parse(Files.readString(loopA)), Parser.parse(Files.readString(loopB)))),
            Workspace.memberTypes(List.of(
                Parser.parse(Files.readString(loopA)), Parser.parse(Files.readString(loopB)))))
            .source();
        assertTrue(aSrc.contains("class LoopA"), aSrc);
    }

    // --- Fix 2: numeric matrix ---

    @Test
    void constructorDecimalParam_coercesIntegerArgument() throws Exception {
        // new Order(true, 0): the third-position... here the ctor param `cost` is Decimal; the
        // Integer literal 0 must widen, or the call won't bind to the BigDecimal ctor param.
        Path order = probe("Order", """
            public class Order {
                public Decimal cost;
                public Order(Boolean active, Decimal cost) { this.cost = cost; }
                public static Decimal make() {
                    Order o = new Order(true, 0);
                    return o.cost;
                }
            }
            """);
        Object result = Workspace.compile(List.of(order)).load("Order").getMethod("make").invoke(null);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("0")));
    }

    @Test
    void decimalGreaterThanInteger_compilesAndRuns() throws Exception {
        // dec > 5: BigDecimal has no '>' operator -> compareTo. 7 > 5 must be true.
        Path cmp = probe("CmpA", """
            public class CmpA {
                public static Boolean go() {
                    Decimal dec = 7;
                    return dec > 5;
                }
            }
            """);
        Object result = Workspace.compile(List.of(cmp)).load("CmpA").getMethod("go").invoke(null);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void integerLessThanOrEqualDecimal_compilesAndRuns() throws Exception {
        // 5 <= dec with the Decimal on the right; the Integer side widens. 5 <= 3 is false.
        Path cmp = probe("CmpB", """
            public class CmpB {
                public static Boolean go() {
                    Decimal dec = 3;
                    return 5 <= dec;
                }
            }
            """);
        Object result = Workspace.compile(List.of(cmp)).load("CmpB").getMethod("go").invoke(null);
        assertEquals(Boolean.FALSE, result);
    }

    @Test
    void mixedTernaryIntoDecimalLocal_widensAndRuns() throws Exception {
        // Decimal d = cond ? amount : 0 — one branch Decimal, the other Integer; per-branch widen.
        Path tern = probe("MixTern", """
            public class MixTern {
                public static Decimal go() {
                    Decimal amount = 9;
                    Boolean cond = false;
                    Decimal d = cond ? amount : 0;
                    return d;
                }
            }
            """);
        Object result = Workspace.compile(List.of(tern)).load("MixTern").getMethod("go").invoke(null);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("0")));
    }

    @Test
    void decimalFieldInitializerZero_widensAndRuns() throws Exception {
        // private Decimal total = 0; the field initializer is an Integer literal into a Decimal.
        Path ledger = probe("Ledger", """
            public class Ledger {
                private Decimal total = 0;
                public Decimal balance() { return this.total; }
                public static Decimal go() { return new Ledger().balance(); }
            }
            """);
        Object result = Workspace.compile(List.of(ledger)).load("Ledger").getMethod("go").invoke(null);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("0")));
    }

    @Test
    void integerCastOfDecimal_narrowsViaIntValue() throws Exception {
        // (Integer) someDecimal: a BigDecimal can't be Java-cast to Integer; emit intValue().
        Path cast = probe("Narrow", """
            public class Narrow {
                public static Integer go() {
                    Decimal dec = 42;
                    return (Integer) dec;
                }
            }
            """);
        Object result = Workspace.compile(List.of(cast)).load("Narrow").getMethod("go").invoke(null);
        assertEquals(42, result);
    }

    // --- Fix 3: residual numeric widen contexts (this-field assign, static-call args, Integer.valueOf) ---

    @Test
    void thisDecimalFieldAssign_widensIntegerAndRuns() throws Exception {
        // this.minimumOrderValue = 0 — a same-class Decimal field assigned an Integer literal via
        // `this`. The bare-Name path widens `field = 0`; the this-rooted form must widen too, or
        // javac rejects "Integer cannot be converted to Decimal".
        Path store = probe("StoreConfig", """
            public class StoreConfig {
                public Decimal minimumOrderValue;
                public StoreConfig() {
                    this.minimumOrderValue = 0;
                }
                public Decimal minimum() { return this.minimumOrderValue; }
                public static Decimal go() { return new StoreConfig().minimum(); }
            }
            """);
        Object result = Workspace.compile(List.of(store)).load("StoreConfig").getMethod("go").invoke(null);
        assertTrue(result instanceof Decimal, "expected a Decimal, got " + result);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("0")));
    }

    @Test
    void thisDecimalFieldAssign_withShadowingIntegerLocal_widensViaFieldType() throws Exception {
        // A LOCAL `Integer x` shadows the FIELD `Decimal x`. `this.x = 0` must widen via the FIELD
        // type (Decimal), while a bare `x = 0` keeps targeting the Integer local (no widen). Both
        // assignments compile and the field ends up a Decimal 0 — no shadowing regression.
        Path shadow = probe("Shadow", """
            public class Shadow {
                public Decimal x;
                public Decimal set() {
                    Integer x = 5;
                    x = 0;
                    this.x = 0;
                    return this.x;
                }
                public static Decimal go() { return new Shadow().set(); }
            }
            """);
        Object result = Workspace.compile(List.of(shadow)).load("Shadow").getMethod("go").invoke(null);
        assertTrue(result instanceof Decimal, "expected a Decimal, got " + result);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("0")));
    }

    @Test
    void staticCallDecimalParams_coerceIntegerArguments() throws Exception {
        // Factory.make('k', 10, 10) where make(String, Decimal, Decimal) is STATIC. The target is a
        // bare type-name (typeOf reports null by design), so the param lookup must key off that name
        // or the two Integer args never widen into the Decimal params.
        Path factory = probe("Factory", """
            public class Factory {
                public static Decimal make(String key, Decimal price, Decimal qty) {
                    return price + qty;
                }
                public static Decimal go() { return Factory.make('k', 10, 10); }
            }
            """);
        Object result = Workspace.compile(List.of(factory)).load("Factory").getMethod("go").invoke(null);
        assertEquals(0, ((Decimal) result).compareTo(Decimal.valueOf("20")));
    }

    @Test
    void staticCall_ambiguousOverload_doesNotCoerce() {
        // STATIC overloads pick(Decimal) vs pick(Integer) disagree at position 0 -> the param key is
        // AMBIGUOUS-poisoned, so the Integer argument must NOT be coerced (it would silently bind the
        // wrong Java overload). The emitted call passes the bare literal, never Decimal.valueOf(7).
        String java = transpile("""
            public class Picker {
                public static Decimal pick(Decimal v) { return v; }
                public static Decimal pick(Integer v) { return v; }
                public static Decimal go() { return Picker.pick(7); }
            }
            """);
        assertTrue(java.contains("Picker.pick(7)"), java);
        assertTrue(!java.contains("Decimal.valueOf(7)"), java);
    }

    @Test
    void integerValueOfDecimal_narrowsViaIntValue() throws Exception {
        // Integer.valueOf(someDecimal): Apex narrows a Decimal; java.lang.Integer.valueOf has no
        // Decimal overload, so emit (dec).intValue() (truncating), mirroring the cast-narrow path.
        Path conv = probe("ToInt", """
            public class ToInt {
                public static Integer go() {
                    Decimal dec = 42;
                    Integer i = Integer.valueOf(dec);
                    return i;
                }
            }
            """);
        Object result = Workspace.compile(List.of(conv)).load("ToInt").getMethod("go").invoke(null);
        assertEquals(42, result);
    }

    // --- Fix 2 regression guards (source-shape, no schema) ---

    @Test
    void integerOnlyArithmetic_staysPrimitive() {
        // Integer-only arithmetic must NOT become Decimal.add / valueOf-wrapped.
        String java = transpile("""
            public class Plain {
                public static Integer go() {
                    Integer a = 2;
                    Integer b = a + 3;
                    return b;
                }
            }
            """);
        assertTrue(java.contains("a + 3"), java);
        assertTrue(!java.contains(".add("), java);
        assertTrue(!java.contains("Decimal.valueOf"), java);
    }

    @Test
    void allIntegerTernaryIntoDecimal_widensPerBranch_notWholeConditional() {
        // Decimal d = cond ? 0 : 1 — BOTH branches Integer, the ternary only needs widening
        // because the surrounding type is Decimal. coerceDecimal recurses into the branches, so
        // each is wrapped (Decimal.valueOf(0) / Decimal.valueOf(1)) rather than the whole
        // conditional going through Decimal.valueOf(Object) (which would lose static typing).
        String java = transpile("""
            public class AllIntTern {
                public static Decimal go() {
                    Boolean cond = true;
                    Decimal d = cond ? 0 : 1;
                    return d;
                }
            }
            """);
        assertTrue(java.contains("Decimal.valueOf(0)"), java);
        assertTrue(java.contains("Decimal.valueOf(1)"), java);
        assertTrue(!java.contains("Decimal.valueOf((cond"), java); // not the whole conditional
    }

    @Test
    void integerRelational_staysPrimitiveOperator() {
        // Integer < Integer must stay the Java '<' operator, never compareTo.
        String java = transpile("""
            public class IntCmp {
                public static Boolean go() {
                    Integer a = 1;
                    return a < 2;
                }
            }
            """);
        assertTrue(java.contains("a < 2"), java);
        assertTrue(!java.contains("compareTo"), java);
    }

    private String transpile(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name())).source();
    }
}
