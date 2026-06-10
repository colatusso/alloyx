// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hello-world of the JVM runtime: Apex -> Java -> compile -> run. */
class HelloTest {
    private static final Path HELLO = Path.of("examples/Hello.cls");

    @Test
    void transpilesToApexLikeJava() throws Exception {
        String source = Files.readString(HELLO);
        Transpiler.Result result = Transpiler.transpile(Parser.parse(source));
        assertTrue(result.source().contains("class Hello"), result.source());
        assertTrue(result.source().contains("System.debug"), result.source());
    }

    @Test
    void runsAndPrintsFive() throws Exception {
        Workspace.Compiled compiled = Workspace.compile(List.of(HELLO));

        PrintStream original = java.lang.System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        java.lang.System.setOut(new PrintStream(buffer));
        try {
            compiled.load("Hello").getMethod("run").invoke(null);
        } finally {
            java.lang.System.setOut(original);
        }
        assertTrue(buffer.toString().contains("DEBUG|5"), buffer.toString());
    }
}
