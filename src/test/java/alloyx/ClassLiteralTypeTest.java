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
 * Apex {@code X.class} is a {@code System.Type} literal, NOT a {@code java.lang.Class}. For a
 * non-sObject type it must emit the runtime {@code Type} so it composes where a {@code Type} is
 * expected — chiefly {@code new List<Type>{ Integer.class }} (e.g. fflib_QualifiedMethod), but also
 * any user/runtime method that takes a {@code System.Type}. A typed-sObject {@code .class} stays a
 * {@code java.lang.Class} (the JSON.deserialize materialization token) — that path is unchanged.
 *
 * <p>All identifiers are invented fixtures — no real schema, no hardcoded type names.
 */
class ClassLiteralTypeTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void listOfTypeFromClassLiterals_compilesAndRuns() throws Exception {
        // the regression that flipped fflib_QualifiedMethod: a List<Type> built from `.class` tokens.
        // Each Integer.class / String.class is a System.Type element, so the list is well-typed.
        Path p = probe("TypeListProbe", """
            public class TypeListProbe {
                public static Integer count() {
                    List<Type> ts = new List<Type>{ Integer.class, String.class, TypeListProbe.class };
                    return ts.size();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("TypeListProbe");
        assertEquals(3, c.getMethod("count").invoke(null));
    }

    @Test
    void classLiteralStringifiesToTypeName() throws Exception {
        // String.valueOf(Integer.class) is the type's name in Apex — the runtime Type's toString().
        Path p = probe("NameProbe", """
            public class NameProbe {
                public static String typeName() {
                    Type t = Integer.class;
                    return String.valueOf(t);
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("NameProbe");
        assertEquals("Integer", c.getMethod("typeName").invoke(null));
    }

    @Test
    void typeTokensCompareByDenotedType() throws Exception {
        // Apex: Integer.class == Integer.class is true; a List<Type> equality compares element types.
        // The runtime Type equals by (case-insensitive) name, so two equal lists of tokens are equal.
        Path p = probe("TypeEqProbe", """
            public class TypeEqProbe {
                public static Boolean sameList() {
                    List<Type> a = new List<Type>{ Integer.class, String.class };
                    List<Type> b = new List<Type>{ Integer.class, String.class };
                    return a == b;
                }
                public static Boolean sameToken() {
                    return Integer.class == Integer.class;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("TypeEqProbe");
        assertTrue((Boolean) c.getMethod("sameToken").invoke(null), "Integer.class == Integer.class");
        assertTrue((Boolean) c.getMethod("sameList").invoke(null), "equal lists of type tokens");
    }

    @Test
    void classLiteralChecksClean() throws Exception {
        // the full repro shape checks with zero diagnostics (no inference-bound conflict).
        Path p = probe("Selector", """
            public class Selector {
                public static List<Type> argTypes() {
                    return new List<Type>{ Integer.class, String.class, Selector.class };
                }
            }
            """);
        assertEquals(List.of(), Workspace.check(p, null, dir.resolve(".apexcache")),
            "a List<Type> of class literals must check clean");
    }
}
