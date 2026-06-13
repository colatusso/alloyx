// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RC2: a {@code Typed x = Typed.staticCall(...)} VarDecl whose declared type sits OUTSIDE the
 * compile set degrades the type to the dynamic {@code SObject}. Previously the VarDecl fell to the
 * {@code var}-inference else-branch AND the just-declared local was bound into {@code locals}
 * BEFORE its own initializer was emitted, so the static-call target {@code Typed} — case-insensitive
 * against {@code locals} — matched the new local and emitted {@code var x = x.staticCall(...)}, which
 * javac rejects as a self-referencing {@code var}.
 *
 * <p>The fix is two-part: (1) a VarDecl that carried a source-declared type emits the MAPPED type
 * (never {@code var}); (2) the local name is bound into {@code locals} AFTER its initializer is
 * emitted, so the static-call target keeps the type/static shape inside its own initializer. A local
 * sharing the type's name only in a LATER statement still resolves as the local (no precedence
 * regression). All identifiers are invented fixtures — nothing hardcoded.
 */
class SelfReferenceVarDeclTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private String transpile(String name, String body) {
        ClassDecl d = Parser.parse(body);
        return Transpiler.transpile(d, java.util.Set.of(name),
            new alloyx.runtime.SchemaCache(new alloyx.runtime.UnconnectedGateway()),
            java.util.Set.of(),
            Workspace.memberIndex(List.of(d)),
            Workspace.memberTypes(List.of(d))).source();
    }

    @Test
    void selfReferencingStaticInitDegradedEmission() throws Exception {
        // CachedItems is NOT in the compile set (a dep outside the check's direct set), so its TYPE
        // degrades to the dynamic SObject. The init is a STATIC call on the very name being declared
        // (only the case differs from the local). The emission must never be the self-referencing
        // `var x = x.fromJson(...)`: it carries the mapped declared type and keeps the static shape.
        String src = transpile("CheckoutCtrl", """
            public class CheckoutCtrl {
                public void load(String payload) {
                    CachedItems cachedItems = CachedItems.fromJson(payload);
                    System.debug(cachedItems);
                }
            }
            """);
        // never the self-referencing var form
        assertFalse(src.contains("var cachedItems = cachedItems."),
            "self-referencing var emitted:\n" + src);
        // the declared (mapped, degraded) type, not var, on the LHS
        assertTrue(src.contains("SObject cachedItems = "), src);
        // the STATIC-call shape on the TYPE name, not the local-receiver shape
        assertTrue(src.contains("CachedItems.fromJson(payload)"), src);
    }

    @Test
    void selfReferencingStaticInitCompilesWhenDepPresent() throws Exception {
        // The same shape, with the static-call target available in the compile set: a class whose
        // name differs from the local only in case. Before the fix the local was bound BEFORE its
        // own initializer, so the case-insensitive lookup rewrote `Cache.load(...)` to
        // `cache.load(...)` (a self-referencing `var`) and javac rejected it. It must now compile and
        // run, resolving the static call to the TYPE.
        Object out = Workspace.compile(List.of(
            probe("Cache", """
                public class Cache {
                    public static String load(String payload) { return payload + '!'; }
                }
                """),
            probe("Consumer", """
                public class Consumer {
                    public static String go(String payload) {
                        String cache = Cache.load(payload);
                        return cache;
                    }
                }
                """)))
            .load("Consumer").getMethod("go", String.class).invoke(null, "x");
        assertEquals("x!", out);
    }

    @Test
    void laterSameNameDifferentCaseLocalStillWins() throws Exception {
        // A local declared in an EARLIER statement, then referenced (case-insensitively) by a LATER
        // statement, must keep resolving to the LOCAL — the bind-after-init change must not regress
        // the locals-over-types precedence for names already in scope.
        Object n = Workspace.compile(List.of(probe("LaterLocal", """
            public class LaterLocal {
                public static Integer run() {
                    String widget = 'hello';
                    Integer len = Widget.length();
                    return len;
                }
            }
            """))).load("LaterLocal").getMethod("run").invoke(null);
        assertEquals(Integer.valueOf(5), n);
    }
}
