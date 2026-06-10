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
 * Apex is fully case-insensitive: `IF`, `For`, `RETURN`, `TRUE`, `Insert` are the
 * same keywords as their lowercase forms. The parser must match keywords/literals
 * case-insensitively while preserving the original case of identifiers (names,
 * member access) wherever they flow into the AST.
 */
class CaseInsensitivityTest {

    @TempDir
    Path dir;

    // transpile + compile + invoke a static method, returning its value
    private Object run(String className, String body) throws Exception {
        Path f = dir.resolve(className + ".cls");
        Files.writeString(f, body);
        return Workspace.compile(List.of(f)).load(className).getMethod("go").invoke(null);
    }

    @Test
    void controlFlowKeywordsInUpperAndMixedCase() throws Exception {
        // IF/ELSE, FOR, WHILE, RETURN all in upper/mixed case, run end-to-end
        Object v = run("Flow", """
            public class Flow {
                public static Integer go() {
                    Integer total = 0;
                    FOR (Integer i = 0; i < 5; i++) {
                        IF (i == 2) {
                            total += 100;
                        } Else {
                            total += i;
                        }
                    }
                    Integer guard = 0;
                    WHILE (guard < 3) {
                        guard++;
                    }
                    RETURN total + guard;
                }
            }
            """);
        // i=0..4: 0 + 1 + 100 + 3 + 4 = 108, plus guard=3 -> 111
        assertEquals(Integer.valueOf(111), v);
    }

    @Test
    void booleanAndNullLiteralsInMixedCase() throws Exception {
        Object v = run("Lits", """
            public class Lits {
                public static Integer go() {
                    Boolean a = TRUE;
                    Boolean b = False;
                    String s = NULL;
                    Integer r = 0;
                    if (a && !b && s == null) {
                        r = 7;
                    }
                    return r;
                }
            }
            """);
        assertEquals(Integer.valueOf(7), v);
    }

    @Test
    void mixedCaseClassAndMethodModifiers() throws Exception {
        // `Public Class`, `PUBLIC STATIC` must be recognized as modifiers/keywords
        Object v = run("Mods", """
            Public Class Mods {
                PUBLIC STATIC Integer go() {
                    return 42;
                }
            }
            """);
        assertEquals(Integer.valueOf(42), v);
    }

    @Test
    void mixedCaseDmlTranspilesToLowercaseDatabaseCall() throws Exception {
        // `Insert acc;` must emit the lowercase runtime call Database.insert(...)
        String java = Transpiler.transpile(Parser.parse("""
            public class DmlBox {
                public static void go() {
                    Account acc = new Account(Name = 'x');
                    Insert acc;
                    UPDATE acc;
                }
            }
            """)).source();
        assertTrue(java.contains("Database.insert("), java);
        assertTrue(java.contains("Database.update("), java);
    }

    @Test
    void identifierStartingWithKeywordStaysIdentifier() throws Exception {
        // regression guard: `iffy`, `returnValue`, `forEach` merely START with a
        // keyword and must parse as plain identifiers, not keywords
        Object v = run("Idents", """
            public class Idents {
                public static Integer go() {
                    Integer iffy = 3;
                    Integer returnValue = 4;
                    Integer forEach = 5;
                    return iffy + returnValue + forEach;
                }
            }
            """);
        assertEquals(Integer.valueOf(12), v);
    }

    @Test
    void identifierCaseIsPreservedInGeneratedJava() throws Exception {
        // the comparison is case-insensitive, but identifier TEXT must be preserved
        // verbatim — a field named `myValue` must stay `myValue`, not be lowercased
        String java = Transpiler.transpile(Parser.parse("""
            public class Keep {
                public static Integer go() {
                    Integer myValue = 9;
                    return myValue;
                }
            }
            """)).source();
        assertTrue(java.contains("myValue"), java);
    }
}
