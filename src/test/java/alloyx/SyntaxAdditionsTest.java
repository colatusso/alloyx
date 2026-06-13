// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Six pure-Apex-syntax parser additions, each parsed, transpiled, compiled AND run:
 * <ol>
 *   <li>{@code switch on / when} (literal lists + {@code when else}, null-safe dispatch);
 *   <li>{@code ===} / {@code !==} exact (reference) equality vs value {@code ==};
 *   <li>{@code <>} legacy inequality (synonym of {@code !=});
 *   <li>C-style {@code for} with multiple declarators ({@code for (Integer i=0, len=n; ...)});
 *   <li>{@code transient} modifier on fields and locals (dropped in emission);
 *   <li>{@code ??} null-coalescing (left when non-null, right when left null).
 * </ol>
 * Fixtures use generic neutral names; every probe both compiles and executes.
 */
class SyntaxAdditionsTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private Object run(String name, String method, Object... args) throws Exception {
        return runWith(List.of(), name, method, args);
    }

    private Object runWith(List<Path> extra, String name, String method, Object... args)
            throws Exception {
        List<Path> files = new java.util.ArrayList<>(extra);
        // the probe class is the LAST file; its source is supplied via probe() by the caller
        Class<?> c = Workspace.compile(files).load(name);
        Class<?>[] sig = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) sig[i] = args[i].getClass();
        return c.getMethod(method, sig).invoke(null, args);
    }

    // --- 1. switch on / when ---------------------------------------------------------------

    @Test
    void switchOnIntegerLiterals_dispatchesAndFallsThroughToElse() throws Exception {
        Path p = probe("HttpRouter", """
            public class HttpRouter {
                public static String classify(Integer code) {
                    String result;
                    switch on code {
                        when 200, 201 { result = 'ok'; }
                        when 400, 404, 422 { result = 'badRequest'; }
                        when 500, 501, 502 { result = 'serverError'; }
                        when else { result = 'unknown'; }
                    }
                    return result;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("HttpRouter");
        assertEquals("ok", c.getMethod("classify", Integer.class).invoke(null, 201));
        assertEquals("badRequest", c.getMethod("classify", Integer.class).invoke(null, 404));
        assertEquals("serverError", c.getMethod("classify", Integer.class).invoke(null, 500));
        assertEquals("unknown", c.getMethod("classify", Integer.class).invoke(null, 418));
    }

    @Test
    void switchOnString_dispatchesByValue() throws Exception {
        Path p = probe("LobRouter", """
            public class LobRouter {
                public static String bucket(String name) {
                    String result = 'init';
                    switch on name {
                        when 'PVL' { result = 'pvl'; }
                        when 'CVL', 'MCO' { result = 'grouped'; }
                        when else { result = 'others'; }
                    }
                    return result;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("LobRouter");
        assertEquals("pvl", c.getMethod("bucket", String.class).invoke(null, "PVL"));
        assertEquals("grouped", c.getMethod("bucket", String.class).invoke(null, "MCO"));
        assertEquals("others", c.getMethod("bucket", String.class).invoke(null, "ZZZ"));
    }

    @Test
    void switchOnNullSubject_matchesElse() throws Exception {
        // a null subject must not NPE — Objects.equals is null-safe, so it falls to `when else`.
        Path p = probe("NullSwitch", """
            public class NullSwitch {
                public static String pick(String name) {
                    String result = 'init';
                    switch on name {
                        when 'A' { result = 'a'; }
                        when else { result = 'fallback'; }
                    }
                    return result;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("NullSwitch");
        String[] arg = {null};
        assertEquals("fallback", c.getMethod("pick", String.class).invoke(null, (Object) arg[0]));
    }

    // --- 2. === / !== exact equality vs == value equality ----------------------------------

    @Test
    void tripleEquals_isReferenceIdentity_notValueEquality() throws Exception {
        // two equal-VALUE Strings: == is true (value), === is false (different references).
        Path p = probe("Identity", """
            public class Identity {
                public static Boolean valueEq(String a, String b) { return a == b; }
                public static Boolean refEq(String a, String b) { return a === b; }
                public static Boolean refNe(String a, String b) { return a !== b; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Identity");
        String x = new String("hi");
        String y = new String("hi"); // equal value, distinct identity
        assertEquals(Boolean.TRUE, c.getMethod("valueEq", String.class, String.class).invoke(null, x, y));
        assertEquals(Boolean.FALSE, c.getMethod("refEq", String.class, String.class).invoke(null, x, y));
        assertEquals(Boolean.TRUE, c.getMethod("refNe", String.class, String.class).invoke(null, x, y));
        // same reference: === true
        assertEquals(Boolean.TRUE, c.getMethod("refEq", String.class, String.class).invoke(null, x, x));
        assertEquals(Boolean.FALSE, c.getMethod("refNe", String.class, String.class).invoke(null, x, x));
    }

    // --- 3. <> legacy inequality -----------------------------------------------------------

    @Test
    void diamondInequality_behavesAsNotEquals() throws Exception {
        Path p = probe("LegacyNe", """
            public class LegacyNe {
                public static Boolean differs(String a, String b) { return a <> b; }
                public static Boolean notNull(String a) { return a <> null; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("LegacyNe");
        assertEquals(Boolean.TRUE, c.getMethod("differs", String.class, String.class).invoke(null, "x", "y"));
        assertEquals(Boolean.FALSE, c.getMethod("differs", String.class, String.class).invoke(null, "x", "x"));
        assertEquals(Boolean.TRUE, c.getMethod("notNull", String.class).invoke(null, "x"));
    }

    // --- 4. C-style for with multiple declarators ------------------------------------------

    @Test
    void multiDeclaratorFor_registersBothLocals_andRuns() throws Exception {
        Path p = probe("Summer", """
            public class Summer {
                public static Integer total(List<Integer> items) {
                    Integer sum = 0;
                    for (Integer i = 0, len = items.size(); i < len; i++) {
                        sum += items.get(i);
                    }
                    return sum;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Summer");
        alloyx.runtime.List<Integer> items = new alloyx.runtime.List<>();
        items.add(3);
        items.add(4);
        items.add(5);
        assertEquals(Integer.valueOf(12),
            c.getMethod("total", alloyx.runtime.List.class).invoke(null, items));
    }

    // --- 5. transient modifier (fields + locals) -------------------------------------------

    @Test
    void transientOnFieldsAndLocals_compilesAndRuns() throws Exception {
        Path p = probe("Holder", """
            public class Holder {
                transient public String label;
                private transient Integer counter;
                public static Integer build() {
                    transient List<Integer> nums = new List<Integer>();
                    nums.add(7);
                    transient final Integer bonus = 5;
                    return nums.get(0) + bonus;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Holder");
        assertEquals(Integer.valueOf(12), c.getMethod("build").invoke(null));
        // the field still exists (modifier dropped, field kept)
        assertTrue(java.util.Arrays.stream(c.getDeclaredFields()).anyMatch(f -> f.getName().equals("label")));
    }

    // --- 6. ?? null-coalescing -------------------------------------------------------------

    @Test
    void nullCoalescing_returnsLeftWhenPresent_rightWhenNull() throws Exception {
        Path p = probe("Coalesce", """
            public class Coalesce {
                public static String pick(String a, String b) { return a ?? b; }
                public static String chain(String a, String b, String c) { return a ?? b ?? c; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Coalesce");
        assertEquals("left", c.getMethod("pick", String.class, String.class).invoke(null, "left", "right"));
        // left null -> right
        String[] holder = {null};
        assertEquals("right",
            c.getMethod("pick", String.class, String.class).invoke(null, holder[0], "right"));
        // right-associative chain: first non-null wins
        assertEquals("third",
            c.getMethod("chain", String.class, String.class, String.class)
                .invoke(null, holder[0], holder[0], "third"));
    }

    @Test
    void nullCoalescing_onSObjectFieldReadFallback() throws Exception {
        // a ?? over a nullable expression with a default — exercises the typer's left-type result
        Path p = probe("Defaulter", """
            public class Defaulter {
                public static Integer sizeOr(List<String> items, Integer fallback) {
                    Integer n = items == null ? null : items.size();
                    return n ?? fallback;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Defaulter");
        alloyx.runtime.List<String> items = new alloyx.runtime.List<>();
        items.add("a");
        items.add("b");
        assertEquals(Integer.valueOf(2),
            c.getMethod("sizeOr", alloyx.runtime.List.class, Integer.class).invoke(null, items, 9));
        assertEquals(Integer.valueOf(9),
            c.getMethod("sizeOr", alloyx.runtime.List.class, Integer.class).invoke(null, (Object) null, 9));
    }
}
