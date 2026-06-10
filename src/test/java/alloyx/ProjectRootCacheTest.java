// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.UnconnectedGateway;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache must anchor to the project ROOT (the dir holding {@code alloyx.json} or an
 * existing {@code .apexcache}), walking up from the target .cls — not the raw CWD. When
 * the VS Code extension runs us with {@code cwd=dirname(file)} in a nested layout, a
 * CWD-relative cache silently misses the synced schema, so every sObject degrades to the
 * untyped generic SObject and the dev gets a flood of false type errors. Anchoring to the
 * root fixes that; with no marker we fall back to CWD-relative (flat layouts unchanged).
 */
class ProjectRootCacheTest {

    @TempDir
    Path dir;

    // Database.gateway() is a shared static; pin it to the unconnected default so these tests
    // read schema only from disk (the synced-cache path) and never depend on test ordering.
    @BeforeEach
    void resetGateway() {
        Database.setGateway(new UnconnectedGateway());
    }

    @AfterEach
    void restoreStreams() {
        Database.setGateway(new UnconnectedGateway());
    }

    // A minimal synced schema on disk: a global list (so knownSObjects() != null) and one
    // described object, exactly the shape `allx schema sync` writes (see SchemaCache).
    private void writeSchema(Path cacheDir, String object) throws Exception {
        Path schema = cacheDir.resolve("schema");
        Files.createDirectories(schema);
        Files.writeString(schema.resolve("_global.json"),
            "{\"fetchedAt\":" + Long.MAX_VALUE + ",\"sobjects\":[\"" + object + "\"]}",
            StandardCharsets.UTF_8);
        Files.writeString(schema.resolve(object + ".json"),
            "{\"fetchedAt\":" + Long.MAX_VALUE + ",\"fields\":{\"Name\":\"String\"}}",
            StandardCharsets.UTF_8);
    }

    private Path writeCls(Path at, String name, String body) throws Exception {
        Files.createDirectories(at);
        Path f = at.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private static final String USES_ACCOUNT = """
        public class Uses {
            public String go() {
                Account a = new Account();
                return a.Name;
            }
        }
        """;

    /** alloyx.json several levels above the .cls marks the root; the cache lands there. */
    @Test
    void rootFoundViaAlloyxJsonAboveTarget() throws Exception {
        Files.writeString(dir.resolve("alloyx.json"), "{\"org\":\"acme\"}");
        Path nested = dir.resolve("force-app/main/default/classes");
        Path target = writeCls(nested, "Uses", USES_ACCOUNT);
        // schema synced at the ROOT, not next to the .cls
        writeSchema(dir.resolve(".apexcache"), "Account");

        Path resolved = Config.cacheDir(target);
        assertEquals(dir.resolve(".apexcache").toRealPath(), resolved.toRealPath(),
            "cache must anchor at the alloyx.json root, not the .cls dir");

        Workspace.check(target, null, resolved);
        // generated typed Account proves the schema at the root was found and used
        assertTrue(Files.exists(resolved.resolve("Account.java")),
            "the root-synced schema must type Account (generated class in the root cache)");
    }

    /** With no alloyx.json, an existing .apexcache dir above the target marks the root. */
    @Test
    void rootFoundViaExistingApexcacheDir() throws Exception {
        Path nested = dir.resolve("classes/sub");
        Path target = writeCls(nested, "Uses", USES_ACCOUNT);
        // the marker: an existing .apexcache at the root, with a synced schema in it
        writeSchema(dir.resolve(".apexcache"), "Account");

        Path resolved = Config.cacheDir(target);
        assertEquals(dir.resolve(".apexcache").toRealPath(), resolved.toRealPath(),
            "an existing .apexcache above the .cls must mark the root");
        Workspace.check(target, null, resolved);
        assertTrue(Files.exists(resolved.resolve("Account.java")),
            "schema in the discovered .apexcache must type Account");
    }

    /** No marker anywhere: fall back to CWD-relative .apexcache (retrocompat for flat layouts). */
    @Test
    void noMarkerFallsBackToCwdRelative() throws Exception {
        Path target = writeCls(dir, "Uses", USES_ACCOUNT);
        Path resolved = Config.cacheDir(target);
        // CWD-relative: the absolute form of the bare ".apexcache" the engine has always used
        assertEquals(Path.of(".apexcache").toAbsolutePath(), resolved,
            "no alloyx.json/.apexcache anywhere -> the historical CWD-relative cache");
    }

    /** Referencing an sObject with no schema loaded warns exactly once. */
    @Test
    void warnsOnceWhenNoSchemaLoaded() throws Exception {
        Path target = writeCls(dir, "Uses", USES_ACCOUNT);
        Path cacheDir = dir.resolve(".apexcache"); // empty -> no schema
        String err = captureCheckErr(target, cacheDir);
        assertEquals(1, countWarnings(err), "exactly one schema-not-loaded warning: " + err);
        assertTrue(err.contains("no org schema loaded"), "warning text: " + err);
    }

    /** With a synced schema present, the warning must NOT fire. */
    @Test
    void noWarningWhenSchemaPresent() throws Exception {
        Path target = writeCls(dir, "Uses", USES_ACCOUNT);
        Path cacheDir = dir.resolve(".apexcache");
        writeSchema(cacheDir, "Account");
        String err = captureCheckErr(target, cacheDir);
        assertFalse(err.contains("no org schema loaded"),
            "a loaded schema must silence the warning: " + err);
    }

    /** Code that references no sObject must never warn, even with no schema. */
    @Test
    void noWarningWhenNoSObjectReferenced() throws Exception {
        Path target = writeCls(dir, "Plain", """
            public class Plain {
                public Integer add(Integer a, Integer b) { return a + b; }
            }
            """);
        String err = captureCheckErr(target, dir.resolve(".apexcache"));
        assertFalse(err.contains("no org schema loaded"),
            "no sObject referenced -> no warning: " + err);
    }

    private String captureCheckErr(Path target, Path cacheDir) throws Exception {
        PrintStream original = java.lang.System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        java.lang.System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            Workspace.check(target, null, cacheDir);
        } finally {
            java.lang.System.setErr(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private int countWarnings(String err) {
        int count = 0;
        int at = 0;
        while ((at = err.indexOf("no org schema loaded", at)) >= 0) {
            count++;
            at += "no org schema loaded".length();
        }
        return count;
    }
}
