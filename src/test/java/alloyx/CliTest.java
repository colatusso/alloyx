// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
    @TempDir
    Path dir;

    @AfterEach
    void resetRuntimeContext() {
        Database.setGateway(new alloyx.runtime.UnconnectedGateway());
    }

    @org.junit.jupiter.api.Test
    void runMarksCompiledIsTestMethodBeforeInvocation() throws Exception {
        Path file = fixture("Probe", """
            public class Probe {
                @isTest
                public static void pure() {
                    System.debug(Test.isRunningTest());
                }
            }
            """);

        String output = captureOutput(() -> Cli.main(new String[]{
            "run", file.toString(), "--method", "Probe.pure"}));

        assertTrue(output.contains("DEBUG|true"), output);
        assertFalse(Test.isRunningTest());
    }

    @org.junit.jupiter.api.Test
    void runBlocksAnnotatedDmlBeforeOrgAuth() throws Exception {
        Path file = fixture("Probe", """
            public class Probe {
                @isTest
                public static void writes() {
                    SObject row = new SObject('Account');
                    Database.insert(row);
                }
            }
            """);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
            () -> Cli.main(new String[]{
                "run", file.toString(), "--method", "Probe.writes", "--org", "not-configured"}));

        UnsupportedOperationException cause = assertInstanceOf(
            UnsupportedOperationException.class, error.getCause());
        assertTrue(cause.getMessage().contains("disabled during local @isTest"),
            cause.getMessage());
        assertFalse(Test.isRunningTest());
    }

    @org.junit.jupiter.api.Test
    void runBlocksDirectGatewayDmlBeforeOrgAuth() throws Exception {
        Path file = fixture("Probe", """
            public class Probe {
                @isTest
                public static void writesDirectly() {
                    Database.gateway().insert(new List<SObject>());
                }
            }
            """);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
            () -> Cli.main(new String[]{
                "run", file.toString(), "--method", "Probe.writesDirectly",
                "--org", "not-configured"}));

        UnsupportedOperationException cause = assertInstanceOf(
            UnsupportedOperationException.class, error.getCause());
        assertTrue(cause.getMessage().contains("SalesforceGateway.insert"), cause.getMessage());
        assertFalse(Test.isRunningTest());
    }

    @org.junit.jupiter.api.Test
    void evalTestFlagScopesOnlyThatAnonymousInvocation() throws Exception {
        String tested = eval("--test");
        String ordinary = eval();

        assertTrue(tested.contains("DEBUG|true"), tested);
        assertTrue(ordinary.contains("DEBUG|false"), ordinary);
        assertFalse(Test.isRunningTest());
    }

    @org.junit.jupiter.api.Test
    void evalTestBlocksDmlBeforeOrgAuth() throws Exception {
        Files.createDirectories(dir.resolve(".apexcache"));
        String javaExe = Path.of(java.lang.System.getProperty("java.home"), "bin",
            java.lang.System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(
            javaExe,
            "-cp", java.lang.System.getProperty("java.class.path"),
            "alloyx.Cli",
            "eval", "--stdin", "--test", "--dir", dir.toString(),
            "--org", "not-configured")
            .redirectErrorStream(true)
            .start();
        child.getOutputStream().write(
            "SObject row = new SObject('Account'); Database.insert(row);\n"
                .getBytes(StandardCharsets.UTF_8));
        child.getOutputStream().close();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(1, child.waitFor(), output);
        assertTrue(output.contains("disabled during local @isTest"), output);
        assertFalse(output.contains("sf"), output);
    }

    private Path fixture(String name, String source) throws Exception {
        Files.createDirectories(dir.resolve(".apexcache"));
        Path file = dir.resolve(name + ".cls");
        Files.writeString(file, source);
        return file;
    }

    private String eval(String... flags) throws Exception {
        Files.createDirectories(dir.resolve(".apexcache"));
        InputStream originalIn = java.lang.System.in;
        ByteArrayInputStream input = new ByteArrayInputStream(
            "System.debug(Test.isRunningTest());\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream originalOut = java.lang.System.out;
        try {
            java.lang.System.setIn(input);
            java.lang.System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "eval", "--stdin", "--dir", dir.toString()));
            command.addAll(java.util.List.of(flags));
            Cli.main(command.toArray(String[]::new));
        } finally {
            java.lang.System.setIn(originalIn);
            java.lang.System.setOut(originalOut);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String captureOutput(ThrowingRunnable action) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream originalOut = java.lang.System.out;
        try {
            java.lang.System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            java.lang.System.setOut(originalOut);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
