// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Apex method calls are case-INSENSITIVE: {@code s.toUppercase()},
 * {@code s.subString(1)}, {@code list.deepClone()} are all legal even though the
 * underlying Java method is spelled {@code toUpperCase}/{@code substring}/{@code deepClone}.
 * Static platform calls already fold case-insensitively; these probes pin the SAME
 * behavior for INSTANCE calls on runtime/JDK-backed receivers (String, List/Set/Map,
 * Decimal, Date/Datetime), where the name was previously emitted verbatim and javac failed.
 *
 * <p>When the receiver type is unknown (a dynamic SObject or user class), the name must be
 * left exactly as written — case folding only happens when the typer resolves the receiver
 * to a runtime/JDK-backed class.
 */
class InstanceMethodCaseFoldTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private Object run(String name, String body) throws Exception {
        return Workspace.compile(List.of(probe(name, body))).load(name).getMethod("go").invoke(null);
    }

    @Test
    void stringInstanceMethodsFoldToJdkCasing() throws Exception {
        // toUppercase/subString/toLowerCase differ only in case from the java.lang.String names
        Object v = run("Strs", """
            public class Strs {
                public static String go() {
                    String s = 'Hello World';
                    return s.toUppercase().subString(0, 5).toLowerCase();
                }
            }
            """);
        assertEquals("hello", v);
    }

    @Test
    void listInstanceMethodFoldsToRuntimeCasing() throws Exception {
        // isEmpty/size differ only in case from the runtime List's (ArrayList's) Java methods
        Object v = run("Lst", """
            public class Lst {
                public static Integer go() {
                    List<String> a = new List<String>();
                    a.ADD('x');
                    a.Add('y');
                    if (a.IsEmpty()) {
                        return 0;
                    }
                    return a.SIZE();
                }
            }
            """);
        assertEquals(Integer.valueOf(2), v);
    }

    @Test
    void correctlyCasedCallIsUnchanged() throws Exception {
        // exact-match short-circuit: a correctly-cased call must emit verbatim
        String java = Transpiler.transpile(Parser.parse("""
            public class Keep {
                public static String go() {
                    String s = 'abc';
                    return s.toUpperCase();
                }
            }
            """)).source();
        assertTrue(java.contains(".toUpperCase()"), java);
    }

    @Test
    void stringStaticMethodFoldsToStringsHelperCasing() throws Exception {
        // String.valueof(x) routes to the Strings helper; the method name must fold case-insensitively
        // to the helper's canonical valueOf (Apex is case-insensitive for statics too).
        String java = Transpiler.transpile(Parser.parse("""
            public class S2 {
                public static String go(Object o) {
                    return String.valueof(o);
                }
            }
            """)).source();
        assertTrue(java.contains("Strings.valueOf(o)"), java);
    }

    @Test
    void unknownReceiverLeavesNameAsWritten() throws Exception {
        // a call on an untyped value (user-class method, unknown receiver) must NOT be folded —
        // the typer can't resolve a runtime class, so the name stays exactly as the source had it.
        String java = Transpiler.transpile(Parser.parse("""
            public class Dyn {
                public static void go(Object o) {
                    o.doSomethingWeird();
                }
            }
            """)).source();
        assertTrue(java.contains("doSomethingWeird"), java);
    }
}
