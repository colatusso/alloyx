// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three correctness risks the member-type index carried, each compilable-but-wrong and untested:
 *
 * <ol>
 *   <li><b>Overload-sensitive Decimal coercion.</b> Param types are indexed by POSITION only
 *       ({@code (m)#i}, {@code (new)#i}). With overloads differing at the same position —
 *       {@code Pay(Decimal)} declared before {@code Pay(Integer)} — a call {@code new Pay(5)}
 *       used to coerce the Integer literal to {@code Decimal.valueOf(5)} and silently bind the
 *       WRONG (Decimal) Java overload. The fix marks a contested position {@code (ambiguous)} so
 *       no coercion fires and the integer literal binds the Integer overload (Apex's choice).</li>
 *   <li><b>Class name mistaken for an inherited field.</b> The bare-name fallback (typer +
 *       sObjectTypeOf) resolved {@code Pay} to an inherited field named {@code pay} (String),
 *       routing the static call {@code Pay.split(x)} through {@code Strings.split(...)}. The fix
 *       suppresses the inherited-field fallback when the identifier names a KNOWN type.</li>
 *   <li><b>Same-simple-name inner classes colliding in the index.</b> {@code OuterA.Helper} and
 *       {@code OuterB.Helper} both indexed under the simple key {@code Helper}, merging members of
 *       BOTH. The fix poisons a contested simple key so a cross-class read falls through untyped
 *       rather than mis-typing; qualified {@code Outer.Inner} keys stay correct.</li>
 * </ol>
 *
 * <p>An unambiguous overload (same param type at the position in both) must still coerce — the
 * regression guard at the bottom. All identifiers are invented fixtures.
 */
class AmbiguityAndCollisionTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    // --- Finding 1: overload-sensitive coercion ---

    @Test
    void ctorOverload_decimalDeclaredFirst_integerLiteralBindsIntegerOverload() throws Exception {
        // Pay(Decimal) is declared BEFORE Pay(Integer). new Pay(5) must bind the Integer ctor.
        // Each ctor stamps a distinguishing marker into `via` so the bound overload is observable.
        Path pay = probe("Pay", """
            public class Pay {
                public String via;
                public Pay(Decimal x) { this.via = 'decimal'; }
                public Pay(Integer x) { this.via = 'integer'; }
                public static String pick() {
                    Pay p = new Pay(5);
                    return p.via;
                }
            }
            """);
        Object result = Workspace.compile(List.of(pay)).load("Pay").getMethod("pick").invoke(null);
        assertEquals("integer", result);
    }

    @Test
    void methodOverload_decimalDeclaredFirst_integerLiteralBindsIntegerOverload() throws Exception {
        // tag(Decimal) declared before tag(Integer); a bare same-class call tag(5) must bind Integer.
        Path svc = probe("Tagger", """
            public class Tagger {
                public String tag(Decimal x) { return 'decimal'; }
                public String tag(Integer x) { return 'integer'; }
                public static String pick() {
                    Tagger t = new Tagger();
                    return t.tag(5);
                }
            }
            """);
        Object result = Workspace.compile(List.of(svc)).load("Tagger").getMethod("pick").invoke(null);
        assertEquals("integer", result);
    }

    @Test
    void unambiguousDecimalOverload_sameTypeBothPositions_stillCoerces() throws Exception {
        // Both overloads take Decimal at position 0 (they differ at a LATER position). The position
        // is unambiguous, so the Integer literal must STILL widen and the call must compile + run.
        Path svc = probe("Widen", """
            public class Widen {
                public Decimal add(Decimal a) { return a; }
                public Decimal add(Decimal a, Decimal b) { return a; }
                public static Decimal go() {
                    Widen w = new Widen();
                    return w.add(8);
                }
            }
            """);
        Object result = Workspace.compile(List.of(svc)).load("Widen").getMethod("go").invoke(null);
        assertEquals(0, ((alloyx.runtime.Decimal) result).compareTo(alloyx.runtime.Decimal.valueOf("8")));
    }

    // --- Finding 2: class name mistaken for an inherited field ---

    @Test
    void staticCallTargetMatchingInheritedFieldName_emitsStaticCall_notStringsRouting() throws Exception {
        // Base has a String field `pay`. Caller (extends Base) calls Pay.split('a,b') — a STATIC
        // method on the separate class Pay. The bare-name fallback used to resolve Name("Pay") to
        // the inherited String field, routing through Strings.split(pay, ...). With Pay recognized
        // as a known type, it must emit the real static call and return the joined parts.
        Path base = probe("Base", """
            public class Base {
                public String pay;
            }
            """);
        Path payCls = probe("Pay", """
            public class Pay {
                public static String split(String csv) {
                    return 'static:' + csv;
                }
            }
            """);
        Path caller = probe("Caller", """
            public class Caller extends Base {
                public String run() {
                    return Pay.split('a,b');
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(base, payCls, caller));
        Class<?> callerCls = c.load("Caller");
        Object inst = callerCls.getDeclaredConstructor().newInstance();
        assertEquals("static:a,b", callerCls.getMethod("run").invoke(inst));
    }

    @Test
    void staticCallTargetMatchingInheritedField_sourceShape_noStringsRouting() throws Exception {
        // Source-shape guard for the same risk: the emitted Java must NOT route the static call
        // through the Strings helper (Strings.split), which would happen if Name("Pay") resolved
        // to the inherited String field.
        ClassDecl base = Parser.parse("""
            public class Base {
                public String pay;
            }
            """);
        ClassDecl pay = Parser.parse("""
            public class Pay {
                public static String split(String csv) { return csv; }
            }
            """);
        ClassDecl caller = Parser.parse("""
            public class Caller extends Base {
                public String run() { return Pay.split('a,b'); }
            }
            """);
        List<ClassDecl> all = List.of(base, pay, caller);
        String src = Transpiler.transpile(caller, Set.of("Base", "Pay", "Caller"),
            (o, f) -> null, Set.of(),
            Workspace.memberIndex(all), Workspace.memberTypes(all)).source();
        assertTrue(src.contains("Pay.split("), src);
        assertTrue(!src.contains("Strings.split"), src);
    }

    // --- Finding 3: same-simple-name inner classes ---

    @Test
    void sameSimpleNameInnerClasses_crossClassReadsRoundTrip() throws Exception {
        // OuterA.Helper has an Integer `value`; OuterB.Helper has a String `value`. Both index under
        // the simple key "Helper" — a merge used to let a cross-class read be typed against the
        // WRONG Helper. Each outer reads its OWN Helper; both values must round-trip intact.
        Path outerA = probe("OuterA", """
            public class OuterA {
                public class Helper {
                    public Integer value;
                    public Helper(Integer v) { this.value = v; }
                }
                public static Integer read() {
                    Helper h = new Helper(7);
                    return h.value;
                }
            }
            """);
        Path outerB = probe("OuterB", """
            public class OuterB {
                public class Helper {
                    public String value;
                    public Helper(String v) { this.value = v; }
                }
                public static String read() {
                    Helper h = new Helper('hi');
                    return h.value;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(outerA, outerB));
        assertEquals(Integer.valueOf(7), c.load("OuterA").getMethod("read").invoke(null));
        assertEquals("hi", c.load("OuterB").getMethod("read").invoke(null));
    }

    @Test
    void sameSimpleNameInner_extendsKeyResolvesViaQualifiedHeritage() throws Exception {
        // An inner that EXTENDS a sibling inner: OuterA.Sub extends OuterA.Sup, Sup carrying a
        // `tag` field. A same-simple-name inner exists elsewhere (OuterB.Sup with a DIFFERENT
        // member), so the bare `Sup` alias is POISONED. The heritage walk from the qualified inner
        // must still reach OuterA.Sup (Apex sibling-inner scoping) — proven both end-to-end (the
        // inherited field round-trips through a Sub instance) and at the typer (memberType resolves
        // the qualified inner's inherited member while the poisoned simple alias returns null).
        Path outerA = probe("OuterA", """
            public class OuterA {
                public virtual class Sup {
                    public String tag;
                }
                public class Sub extends Sup {
                }
                public static String read() {
                    Sub s = new Sub();
                    s.tag = 'kept';
                    return s.tag;
                }
            }
            """);
        Path outerB = probe("OuterB", """
            public class OuterB {
                public class Sup {
                    public Integer count;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(outerA, outerB));
        assertEquals("kept", c.load("OuterA").getMethod("read").invoke(null));

        // typer-level: the qualified heritage walk resolves the inherited member even though the
        // bare `Sup` alias is poisoned by OuterB.Sup; the poisoned alias itself yields null.
        ClassDecl a = Parser.parse(java.nio.file.Files.readString(outerA));
        ClassDecl b = Parser.parse(java.nio.file.Files.readString(outerB));
        var types = Workspace.memberTypes(List.of(a, b));
        assertEquals(AMBIGUOUS_MARKER, types.get("Sup").keySet().stream().findFirst().orElse(null));
        ExprTyper typer = new ExprTyper((o, f) -> null, Set.of(), Set.of("OuterA", "OuterB"),
            Set.of("Sup", "Sub", "OuterA.Sup", "OuterA.Sub", "OuterB.Sup"), types,
            new java.util.HashMap<>(), () -> new java.util.HashMap<>(), Set.of());
        assertEquals("String", typer.memberType("OuterA.Sub", "tag"));
        assertEquals("String", typer.memberType("Sub", "tag")); // via the alias's recorded owner
    }

    // The class-level ambiguity sentinel a poisoned same-simple-name inner alias carries.
    private static final String AMBIGUOUS_MARKER = "(ambiguous)";
}
