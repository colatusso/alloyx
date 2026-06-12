// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.SchemaProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Item 1 (and 6): Apex lets you qualify platform statics with the {@code System} namespace —
 * {@code System.Test.startTest()}, {@code System.JSON.serialize(x)}, {@code System.Label.X},
 * {@code System.Type.forName(...)}. {@code mapType} already strips {@code System.} for TYPE
 * positions; expression emission must do the same so the chain roots at the platform type
 * instead of reaching javac as a field read {@code System.Test}. Case-insensitive (Apex is):
 * {@code system.test.isrunningtest()} must fold too. The strip only applies when the segment
 * after {@code System.} NAMES a type — {@code System.debug}/{@code System.assert*}/
 * {@code System.runAs} stay direct System members.
 */
class SystemNamespaceExprTest {

    @TempDir
    Path dir;

    private static String transpile(String src) {
        ClassDecl cls = Parser.parse(src);
        SchemaProvider noSchema = new SchemaProvider() {
            @Override public String fieldType(String s, String f) { return null; }
            @Override public boolean isDescribed(String s) { return false; }
            @Override public String canonicalField(String s, String f) { return f; }
            @Override public Map<String, String> fields(String s) { return null; }
        };
        return Transpiler.transpile(cls, Set.of(cls.name()), noSchema, Set.of()).source();
    }

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void systemTestStartStop_dropsSystemPrefix() {
        String java = transpile("""
            public class C {
                public static void go() {
                    System.Test.startTest();
                    System.Test.stopTest();
                }
            }
            """);
        assertTrue(java.contains("Test.startTest()"), java);
        assertTrue(java.contains("Test.stopTest()"), java);
        assertFalse(java.contains("System.Test"), java); // never the field-read shape
    }

    @Test
    void systemTestIsRunningTest_caseInsensitive_compilesAndRuns() throws Exception {
        // the lowercase casing variant `system.test.isrunningtest()` must fold to Test.isRunningTest()
        // (honestly false locally) and run.
        Path p = probe("Guard", """
            public class Guard {
                public static Boolean inTest() {
                    return system.test.isrunningtest();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Guard");
        assertEquals(Boolean.FALSE, c.getMethod("inTest").invoke(null));
    }

    @Test
    void systemJsonSerialize_dropsSystemPrefix_andRuns() throws Exception {
        Path p = probe("Ser", """
            public class Ser {
                public static String go() {
                    return System.JSON.serialize(new Map<String, Object>{ 'a' => 1 });
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Ser");
        assertEquals("{\"a\":1}", c.getMethod("go").invoke(null));
    }

    @Test
    void systemTypeForName_dropsSystemPrefix_andRuns() throws Exception {
        Path p = probe("Refl", """
            public class Refl {
                public static String go() {
                    return System.Type.forName('Account').getName();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Refl");
        assertEquals("Account", c.getMethod("go").invoke(null));
    }

    @Test
    void systemDebug_assert_runAs_stayDirectSystemMembers() {
        // these are METHODS on System, not type names: the strip must not touch them.
        String java = transpile("""
            public class C {
                public static void go() {
                    System.debug('x');
                    System.assert(true);
                    System.assertEquals(1, 1);
                }
            }
            """);
        assertTrue(java.contains("System.debug(\"x\")"), java);
        assertTrue(java.contains("System.assertTrue(true)"), java); // assert -> assertTrue (keyword)
        assertTrue(java.contains("System.assertEquals(1, 1)"), java);
    }

    @Test
    void systemLabel_propRead_emitsLabelGet_andRuns() throws Exception {
        // System.Label.My_Label and Label.My_Label both degrade to the developer name string.
        Path p = probe("Lbl", """
            public class Lbl {
                public static String a() { return System.Label.Greeting; }
                public static String b() { return Label.Farewell; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Lbl");
        assertEquals("Greeting", c.getMethod("a").invoke(null));
        assertEquals("Farewell", c.getMethod("b").invoke(null));
    }
}
