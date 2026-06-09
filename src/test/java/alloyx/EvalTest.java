package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `allx eval` runs an anonymous Apex block: it's wrapped in a throwaway class and
 * compiled together with the workspace classes the snippet references (resolved
 * from the snippet's own tokens, since it has no file on disk). Proven here via the
 * exact primitives the CLI uses — resolveDepsForSource + compile(deps, extraDecls).
 * The wrapper returns a value so the test can assert; the CLI's is void + debug.
 */
class EvalTest {

    @TempDir
    Path dir;

    private Object eval(String snippet) throws Exception {
        ClassDecl scratch = Parser.parse(
            "public class AlloyxScratch { public static Object run() {\n" + snippet + "\n} }");
        List<Path> deps = Workspace.resolveDepsForSource(snippet, dir);
        return Workspace.compile(deps, List.of(scratch)).load("AlloyxScratch")
            .getMethod("run").invoke(null);
    }

    @Test
    void anonymousBlockCallsWorkspaceClass() throws Exception {
        Files.writeString(dir.resolve("MathBox.cls"), """
            public class MathBox {
                public static Integer twice(Integer n) { return n * 2; }
            }
            """);
        assertEquals(Integer.valueOf(42), eval("return MathBox.twice(21);"));
    }

    @Test
    void anonymousBlockRunsLocalLogic() throws Exception {
        assertEquals(Integer.valueOf(10),
            eval("Integer s = 0; for (Integer i = 1; i <= 4; i++) { s += i; } return s;"));
    }

    @Test
    void anonymousBlockPullsOnlyReferencedDeps() throws Exception {
        // an unrelated sibling class must NOT be dragged into the compile set
        Files.writeString(dir.resolve("Used.cls"),
            "public class Used { public static Integer one() { return 1; } }");
        Files.writeString(dir.resolve("Unused.cls"),
            "public class Unused { public static Integer boom() { return 1/0; } }");
        assertEquals(Integer.valueOf(1), eval("return Used.one();"));
    }
}
