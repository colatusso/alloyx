// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Apex safe navigation ({@code ?.}) lowering. The old ternary lowering
 * {@code (a == null ? null : a.b)} had two defects: it re-emitted the target in both the
 * condition and the access (double evaluation + exponential source blowup on deep chains),
 * and its bare {@code null} branch poisoned the static type of the whole expression to
 * {@code <nulltype>}, breaking the next chained hop (and value/condition/argument contexts).
 *
 * <p>The fix lowers {@code a?.b} to {@code Safe.nav(a, x -> x.b)}: the target is evaluated
 * once and the result type is inferred from the lambda body, so no {@code <nulltype>} is ever
 * created. A statement-position safe-nav call (which may be void) lowers to {@code Safe.run}.
 * Fixtures use neutral DTO names (CallbackRequest/Event/Data/Charge) with a String leaf.
 */
class SafeNavigationTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    // The DTO chain shared by most cases: CallbackRequest -> Event -> Data -> Charge -> String.
    private List<Path> dtoChain() throws Exception {
        return new java.util.ArrayList<>(List.of(
            probe("CallbackRequest", "public class CallbackRequest { public Event event; }"),
            probe("Event", "public class Event { public Data data; }"),
            probe("Data", "public class Data { public Charge charge; }"),
            probe("Charge", "public class Charge { public String status; public String expiry; }")));
    }

    @Test
    void deepChain_allNonNull_returnsLeaf() throws Exception {
        // a?.b?.c?.d across user DTOs, every hop non-null -> the leaf value, compiled and run.
        List<Path> files = dtoChain();
        files.add(probe("Run", """
            public class Run {
                public static String go() {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new Event();
                    req.event.data = new Data();
                    req.event.data.charge = new Charge();
                    req.event.data.charge.status = 'paid';
                    return req?.event?.data?.charge?.status;
                }
            }
            """));
        Object v = Workspace.compile(files).load("Run").getMethod("go").invoke(null);
        assertEquals("paid", v);
    }

    @Test
    void deepChain_intermediateNull_isNullNoNpe() throws Exception {
        // Same chain, but an intermediate hop (data) is null -> whole expr null, no NPE.
        List<Path> files = dtoChain();
        files.add(probe("Mid", """
            public class Mid {
                public static String go() {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new Event();
                    return req?.event?.data?.charge?.status;
                }
            }
            """));
        Object v = Workspace.compile(files).load("Mid").getMethod("go").invoke(null);
        assertNull(v);
    }

    @Test
    void rootNull_isNullNoNpe() throws Exception {
        // The root itself is null -> null, no dereference at all.
        List<Path> files = dtoChain();
        files.add(probe("Root", """
            public class Root {
                public static String go() {
                    CallbackRequest req = null;
                    return req?.event?.data?.charge?.status;
                }
            }
            """));
        Object v = Workspace.compile(files).load("Root").getMethod("go").invoke(null);
        assertNull(v);
    }

    @Test
    void safeNavThenPlainMethodCallWithArg_compilesAndRuns() throws Exception {
        // ...?.charge?.expiry.substringBefore('-') — mixed ?. hops then a plain '.' method call
        // with an argument on the (non-null) String leaf. Proves the safe-nav result feeds a call.
        List<Path> files = dtoChain();
        files.add(probe("Mix", """
            public class Mix {
                public static String go() {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new Event();
                    req.event.data = new Data();
                    req.event.data.charge = new Charge();
                    req.event.data.charge.expiry = '2027-05';
                    return req?.event?.data?.charge?.expiry.substringBefore('-');
                }
            }
            """));
        Object v = Workspace.compile(files).load("Mix").getMethod("go").invoke(null);
        assertEquals("2027", v);
    }

    @Test
    void safeNavInBooleanExpression_compilesAndRuns() throws Exception {
        // x?.id != null inside a boolean — the classic <nulltype>-in-context shape. With a
        // non-null id the comparison is true; the && short-circuits cleanly.
        List<Path> files = dtoChain();
        files.add(probe("Bool", """
            public class Bool {
                public static Boolean go(Boolean flag) {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new Event();
                    Boolean ok = flag && req?.event != null;
                    return ok;
                }
            }
            """));
        Object v = Workspace.compile(files).load("Bool")
            .getMethod("go", Boolean.class).invoke(null, Boolean.TRUE);
        assertEquals(Boolean.TRUE, v);
    }

    @Test
    void safeNavAsCallArgument_compilesAndRuns() throws Exception {
        // The safe-nav result passed as a method argument (Object position) — the other
        // <nulltype>-in-context shape. A non-null leaf round-trips through the helper.
        List<Path> files = dtoChain();
        files.add(probe("Arg", """
            public class Arg {
                public static String echo(Object o) { return (String) o; }
                public static String go() {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new Event();
                    req.event.data = new Data();
                    req.event.data.charge = new Charge();
                    req.event.data.charge.status = 'ok';
                    return echo(req?.event?.data?.charge?.status);
                }
            }
            """));
        Object v = Workspace.compile(files).load("Arg").getMethod("go").invoke(null);
        assertEquals("ok", v);
    }

    @Test
    void singleEvaluation_targetMethodSideEffectFiresOnce() throws Exception {
        // getReq() bumps a counter each call. After getReq()?.event the counter must be exactly 1:
        // proof the target is evaluated once (the old ternary re-emitted it -> 2+).
        Path counter = probe("Counter", """
            public class Counter {
                public static Integer calls = 0;
                public static CallbackRequest getReq() {
                    calls = calls + 1;
                    CallbackRequest r = new CallbackRequest();
                    r.event = new Event();
                    return r;
                }
            }
            """);
        List<Path> files = dtoChain();
        files.add(counter);
        files.add(probe("Once", """
            public class Once {
                public static Integer go() {
                    Event e = Counter.getReq()?.event;
                    return Counter.calls;
                }
            }
            """));
        Object v = Workspace.compile(files).load("Once").getMethod("go").invoke(null);
        assertEquals(1, ((Number) v).intValue());
    }

    @Test
    void statementPositionVoidSafeNavCall_compilesAndShortCircuits() throws Exception {
        // obj?.doWork(); as a STATEMENT, where doWork() returns void. A Function lambda can't
        // return void, so this must lower to Safe.run (Consumer). A null target short-circuits
        // (no NPE, work not done); a non-null target runs the work.
        Path worker = probe("Worker", """
            public class Worker {
                public static Integer done = 0;
                public void doWork() { done = done + 1; }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(worker));
        files.add(probe("Stmt", """
            public class Stmt {
                public static Integer go(Boolean present) {
                    Worker w = present ? new Worker() : null;
                    w?.doWork();
                    return Worker.done;
                }
            }
            """));
        var compiled = Workspace.compile(files);
        // null target: short-circuits, done stays 0
        Object zero = compiled.load("Stmt").getMethod("go", Boolean.class).invoke(null, Boolean.FALSE);
        assertEquals(0, ((Number) zero).intValue());
        // non-null target: work runs, done becomes 1
        Object one = compiled.load("Stmt").getMethod("go", Boolean.class).invoke(null, Boolean.TRUE);
        assertEquals(1, ((Number) one).intValue());
    }

    // --- reassigned-local fallback: lambda capture requires effectively-final, Apex doesn't ---

    @Test
    void reassignedLocalInSafeNavArgs_compilesAndRuns() throws Exception {
        // A local reassigned after init (suffix) is NOT effectively final in Java, so it can't be
        // captured by the Safe.nav lambda. The safe-nav over the call must fall back to a ternary.
        Path wrapper = probe("Wrapper", """
            public class Wrapper {
                public String render(String s) { return 'R:' + s; }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(wrapper));
        files.add(probe("Cap", """
            public class Cap {
                public static String go(Wrapper w) {
                    String suffix = 'a';
                    suffix = 'b';
                    return w?.render(suffix);
                }
            }
            """));
        var compiled = Workspace.compile(files);
        Class<?> wcls = compiled.load("Wrapper");
        Object w = wcls.getConstructor().newInstance();
        Object v = compiled.load("Cap").getMethod("go", wcls).invoke(null, w);
        assertEquals("R:b", v);
        // null target short-circuits
        Object n = compiled.load("Cap").getMethod("go", wcls).invoke(null, new Object[]{null});
        assertNull(n);
    }

    @Test
    void classicForVarInSafeNavArgs_accumulatesAcrossIterations() throws Exception {
        // The classic for var `i` is reassigned by the loop update, so it's not effectively final.
        // Referencing it in the safe-nav call args (inside the loop) must fall back to the ternary
        // and still accumulate correctly across the three iterations.
        Path wrapper = probe("Acc", """
            public class Acc {
                public Integer twice(Integer n) { return n * 2; }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(wrapper));
        files.add(probe("Loop", """
            public class Loop {
                public static Integer go(Acc a) {
                    Integer total = 0;
                    for (Integer i = 0; i < 3; i++) {
                        total = total + a?.twice(i);
                    }
                    return total;
                }
            }
            """));
        var compiled = Workspace.compile(files);
        Class<?> acls = compiled.load("Acc");
        Object a = acls.getConstructor().newInstance();
        // twice(0)+twice(1)+twice(2) = 0+2+4 = 6
        Object v = compiled.load("Loop").getMethod("go", acls).invoke(null, a);
        assertEquals(6, ((Number) v).intValue());
    }

    @Test
    void reassignedLocalFallbackSource_carriesTypedNull() throws Exception {
        // The fallback ternary's null branch must be TYPED (a cast to the safe-nav expression's
        // mapped Java type) so it doesn't poison the static type to <nulltype>, mirroring the
        // Safe.nav path's type guarantee. Here render returns String -> the cast is (String).
        Path wrapper = probe("Typed", """
            public class Typed {
                public String render(String s) { return s; }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(wrapper));
        Path cap = probe("CapShape", """
            public class CapShape {
                public static String go(Typed t) {
                    String suffix = 'a';
                    suffix = 'b';
                    return t?.render(suffix);
                }
            }
            """);
        files.add(cap);
        var decls = new java.util.ArrayList<ClassDecl>();
        for (Path f : files) decls.add(Parser.parse(Files.readString(f)));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ClassDecl d : decls) names.add(d.name());
        alloyx.runtime.SchemaProvider schema = new alloyx.runtime.SchemaProvider() {
            public String fieldType(String s, String f) { return null; }
            public boolean isDescribed(String s) { return false; }
            public String canonicalField(String s, String f) { return f; }
            public java.util.Map<String, String> fields(String s) { return null; }
        };
        String out = Transpiler.transpile(Parser.parse(Files.readString(cap)), names, schema,
            java.util.Set.of(), Workspace.memberIndex(decls), Workspace.memberTypes(decls)).source();
        assertTrue(out.contains("(String) null"), "expected typed null in fallback ternary: " + out);
        // and it is the ternary fallback, not Safe.nav, for this call
        assertTrue(out.contains("== null ?"), "expected ternary fallback: " + out);
    }

    @Test
    void mixedNesting_outerTernaryInnerSafeNav_compilesAndRuns() throws Exception {
        // a?.wrap(reassignedLocal)?.tag() — the OUTER hop references a reassigned local (fallback
        // ternary), the INNER hop references nothing reassigned (Safe.nav). The two lowerings must
        // compose: outer ternary wraps an inner Safe.nav, compiles and runs end to end.
        Path inner = probe("Tagged", """
            public class Tagged {
                public String tag() { return 'T:' + body; }
                public String body;
            }
            """);
        Path outer = probe("Maker", """
            public class Maker {
                public Tagged wrap(String s) {
                    Tagged t = new Tagged();
                    t.body = s;
                    return t;
                }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(inner, outer));
        files.add(probe("MixNest", """
            public class MixNest {
                public static String go(Maker m) {
                    String s = 'x';
                    s = 'y';
                    return m?.wrap(s)?.tag();
                }
            }
            """));
        var compiled = Workspace.compile(files);
        Class<?> mcls = compiled.load("Maker");
        Object m = mcls.getConstructor().newInstance();
        Object v = compiled.load("MixNest").getMethod("go", mcls).invoke(null, m);
        assertEquals("T:y", v);
        // null target short-circuits the whole chain
        Object n = compiled.load("MixNest").getMethod("go", mcls).invoke(null, new Object[]{null});
        assertNull(n);
    }

    @Test
    void reassignedLocalInVoidStatementSafeNavCall_guardsCorrectly() throws Exception {
        // Statement-position void call whose arg references a reassigned local: a Function/Consumer
        // lambda can't capture it, and a ternary can't carry a void branch, so this must fall back to
        // a guarded `if (target != null) target.call(arg);`. Null target -> no-op; non-null -> runs.
        Path sink = probe("Sink", """
            public class Sink {
                public static Integer sum = 0;
                public void add(Integer n) { sum = sum + n; }
            }
            """);
        List<Path> files = new java.util.ArrayList<>(List.of(sink));
        files.add(probe("StmtFb", """
            public class StmtFb {
                public static Integer go(Sink s) {
                    Integer k = 1;
                    k = 5;
                    s?.add(k);
                    return Sink.sum;
                }
            }
            """));
        var compiled = Workspace.compile(files);
        Class<?> scls = compiled.load("Sink");
        // null target: short-circuits, sum stays 0
        Object zero = compiled.load("StmtFb").getMethod("go", scls).invoke(null, new Object[]{null});
        assertEquals(0, ((Number) zero).intValue());
        // non-null target: add(5) runs, sum becomes 5
        Object s = scls.getConstructor().newInstance();
        Object five = compiled.load("StmtFb").getMethod("go", scls).invoke(null, s);
        assertEquals(5, ((Number) five).intValue());
    }

    // --- source-shape assertions: single evaluation + no bare-null ternary ---

    @Test
    void deepChainSource_evaluatesTargetOnce_noNulltypeTernary() throws Exception {
        // The emitted source for a deep chain must use Safe.nav (not the bare-null ternary) and
        // must mention the root `req` exactly once — proof the exponential re-emission is gone.
        List<Path> files = dtoChain();
        Path run = probe("Shape", """
            public class Shape {
                public static String go(CallbackRequest req) {
                    return req?.event?.data?.charge?.status;
                }
            }
            """);
        files.add(run);
        ClassDecl cls = Parser.parse(Files.readString(run));
        var decls = new java.util.ArrayList<ClassDecl>();
        for (Path f : files) decls.add(Parser.parse(Files.readString(f)));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ClassDecl d : decls) names.add(d.name());
        alloyx.runtime.SchemaProvider schema = new alloyx.runtime.SchemaProvider() {
            public String fieldType(String s, String f) { return null; }
            public boolean isDescribed(String s) { return false; }
            public String canonicalField(String s, String f) { return f; }
            public java.util.Map<String, String> fields(String s) { return null; }
        };
        String out = Transpiler.transpile(cls, names, schema, java.util.Set.of(),
            Workspace.memberIndex(decls), Workspace.memberTypes(decls)).source();
        assertTrue(out.contains("Safe.nav("), out);
        // the method param `req` appears once in the signature and exactly once in the body
        int occurrences = out.split("req", -1).length - 1;
        assertEquals(2, occurrences, "target re-emitted (exponential blowup): " + out);
    }
}
