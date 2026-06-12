// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import alloyx.runtime.Database;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A `check` invocation must be hermetic: its diagnostics depend ONLY on the current
 * .cls sources (and the synced schema), never on leftover .java/.class a previous
 * invocation of check/run/eval/test left in the shared .apexcache. These repros plant
 * such leftovers and assert the check ignores them.
 */
class CheckIsolationTest {

    @TempDir
    Path dir;

    @BeforeEach
    void offline() {
        // read schema only from disk; never depend on a connected org or test ordering
        Database.setGateway(new UnconnectedGateway());
    }

    private Path write(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private Path cache() {
        return dir.resolve(".apexcache");
    }

    /** Plant a poisoned .java in the cache, as an earlier invocation would have left. */
    private void plantStaleJava(String name, String src) throws Exception {
        Path c = cache();
        Files.createDirectories(c);
        Files.writeString(c.resolve(name + ".java"), src);
    }

    /**
     * Plant a stale .class in the cache by compiling a throwaway source — the artifact a
     * previous run/eval/test leaves, and the one javac would actually consume if the shared
     * dir were ever reachable on its path. Proves the fresh-temp-dir output is immune to it.
     */
    private void plantStaleClass(String name, String src) throws Exception {
        Path c = cache();
        Files.createDirectories(c);
        Path j = c.resolve(name + ".java");
        Files.writeString(j, src);
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        jc.run(null, null, null, "-cp", System.getProperty("java.class.path"),
            "-d", c.toString(), j.toString());
        Files.delete(j); // keep only the stale .class, as a cleaned-up prior invocation would
    }

    /**
     * A self-contained class checks clean even when an unrelated, deliberately broken
     * Stale.java (and an outdated A.java the class never references) sit in the cache.
     */
    @Test
    void selfContainedClassIgnoresStaleCacheFiles() throws Exception {
        Path b = write("B", """
            public class B {
                public Integer go() { return 1 + 2; }
            }
            """);
        plantStaleJava("Stale", "public class Stale { void boom() { return nope; } }");
        plantStaleJava("A", "public class A { void x() { alsoBroken without semicolons } }");

        List<Workspace.Diag> diags = Workspace.check(b, null, cache());
        assertEquals(List.of(), diags,
            "a self-contained class must not inherit a previous invocation's stale errors: " + diags);
    }

    /**
     * When a stale cache file shares the name of a REAL dep, the check must use the
     * FRESH transpilation of that dep's .cls, not the outdated broken cache copy.
     */
    @Test
    void realDepUsesFreshTranspilationNotStaleCache() throws Exception {
        write("A", """
            public virtual class A {
                public Integer base() { return 41; }
            }
            """);
        Path b = write("B", """
            public class B extends A {
                public Integer go() { return base() + 1; }
            }
            """);
        // an OUTDATED, broken A.java from a prior invocation: missing base(), full of errors
        plantStaleJava("A", "public class A { void broken() { return undefined_thing; } }");
        // and a stale A.class whose signature lacks base() — the artifact javac would actually
        // resolve A from if the shared cache ever leaked onto its path (see contamination repro)
        plantStaleClass("A", "public class A { public void old() {} }");

        List<Workspace.Diag> diags = Workspace.check(b, null, cache());
        assertEquals(List.of(), diags,
            "B must compile against the fresh A.cls (base() resolves), not the stale A.java: " + diags);
    }

    /**
     * The structural guarantee behind the two repros above: a check must emit NO .java/.class
     * into the shared project cache — only the schema lives there. Writing compile artifacts
     * into it is exactly what lets one invocation taint the next, so this asserts the cache is
     * untouched (the schema subdir aside) regardless of what the check compiled.
     */
    @Test
    void checkWritesNoArtifactsIntoProjectCache() throws Exception {
        Path b = write("B", """
            public class B {
                public Integer go() { return 1 + 2; }
            }
            """);
        Workspace.check(b, null, cache());
        assertEquals(List.of(), compileArtifactsIn(cache()),
            "check must write no .java/.class into the project cache (it owns only schema/)");
    }

    /**
     * When javac fails on ANOTHER unit (a broken dep) and nothing lands on the target, the
     * target was never type-checked — reporting [] would be a FALSE CLEAN (batch validation
     * found hundreds: the duplicate-class abort family). One synthetic diagnostic must surface.
     */
    @Test
    void brokenDepCannotProduceAFalseClean() throws Exception {
        write("Dep", """
            public class Dep {
                public static Integer broken() { return 'not an integer'; }
            }
            """);
        Path b = write("B", """
            public class B {
                public Integer go() { return Dep.broken(); }
            }
            """);
        List<Workspace.Diag> diags = Workspace.check(b, null, cache());
        assertEquals(1, diags.size(), "expected the synthetic workspace diagnostic, got: " + diags);
        org.junit.jupiter.api.Assertions.assertTrue(
            diags.get(0).message().startsWith("workspace did not compile"),
            "must say the workspace failed, got: " + diags.get(0).message());
    }

    // Every .java/.class anywhere under the cache dir EXCEPT the schema/ subtree (which the cache
    // legitimately owns). Non-empty => a check leaked compile output that a later one could read.
    private List<String> compileArtifactsIn(Path cacheDir) throws Exception {
        if (!Files.isDirectory(cacheDir)) {
            return List.of();
        }
        Path schema = cacheDir.resolve("schema");
        try (Stream<Path> s = Files.walk(cacheDir)) {
            return s.filter(p -> !p.startsWith(schema))
                .map(Path::toString)
                .filter(n -> n.endsWith(".java") || n.endsWith(".class"))
                .sorted().toList();
        }
    }
}
