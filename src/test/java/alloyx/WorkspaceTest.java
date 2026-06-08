package alloyx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace scan must see only the user's Apex, never the tooling/VCS trees
 * that sit beside it. The motivating case: an SFDX project carries
 * {@code .sfdx/tools/.../StandardApexLibrary/System/LoggingLevel.cls} — a
 * {@code global enum LoggingLevel} stub that our parser rejects and that
 * collides with the runtime type of the same name. Walking into it would crash
 * every {@code allx run} in a real project, so hidden (dot-prefixed) directories
 * are pruned.
 */
class WorkspaceTest {

    @TempDir
    Path dir;

    /** A standard-library stub buried under .sfdx must not be collected. */
    @Test
    void clsAt_skipsHiddenDirs() throws Exception {
        Files.writeString(dir.resolve("Greeter.cls"),
            "public class Greeter { public static String hi() { return 'hi'; } }");
        Path stub = dir.resolve(".sfdx/tools/246/StandardApexLibrary/System");
        Files.createDirectories(stub);
        Files.writeString(stub.resolve("LoggingLevel.cls"),
            "global enum LoggingLevel { DEBUG, ERROR }");

        List<Path> found = Workspace.clsAt(dir);
        assertTrue(found.stream().anyMatch(p -> p.getFileName().toString().equals("Greeter.cls")),
            "real class must be found: " + found);
        assertFalse(found.stream().anyMatch(p -> p.getFileName().toString().equals("LoggingLevel.cls")),
            "hidden-dir stub must be skipped: " + found);
    }

    /**
     * Dependency resolution indexes the directory; if it indexed the .sfdx stub,
     * a reference to LoggingLevel would drag the unparseable enum into the
     * compile set. The closure must contain only the real, parseable class.
     */
    @Test
    void resolveDeps_ignoresHiddenStubs() throws Exception {
        Path target = dir.resolve("Audit.cls");
        Files.writeString(target,
            "public class Audit { public static void go() { System.debug(LoggingLevel.ERROR, 'x'); } }");
        Path stub = dir.resolve(".sfdx/tools/246/StandardApexLibrary/System");
        Files.createDirectories(stub);
        Files.writeString(stub.resolve("LoggingLevel.cls"),
            "global enum LoggingLevel { DEBUG, ERROR }");

        List<Path> closure = Workspace.resolveDeps(target);
        assertFalse(closure.stream().anyMatch(p -> p.getFileName().toString().equals("LoggingLevel.cls")),
            "native LoggingLevel must stay out of the user compile set: " + closure);
        assertTrue(closure.stream().anyMatch(p -> p.getFileName().toString().equals("Audit.cls")),
            "the target class must be in its own closure: " + closure);
    }
}
