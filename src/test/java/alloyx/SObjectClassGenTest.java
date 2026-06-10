// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the core of typed-sObject validation: the generated class makes javac
 * reject what the Apex compiler would reject (wrong-typed assignment, unknown
 * field) and accept what is well-typed. The probe class mirrors what the
 * transpiler will emit for {@code a.Field = v} (a typed setter call).
 */
class SObjectClassGenTest {

    @TempDir
    Path dir;

    private int compile(String accountSrc, String probeSrc) throws Exception {
        Files.writeString(dir.resolve("Account.java"), accountSrc);
        Files.writeString(dir.resolve("Probe.java"), probeSrc);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        String cp = System.getProperty("java.class.path");
        return javac.run(null, null, OutputStream.nullOutputStream(),
            "-cp", cp, "-d", dir.toString(),
            dir.resolve("Account.java").toString(), dir.resolve("Probe.java").toString());
    }

    private String account() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Name", "String");
        fields.put("NumberOfEmployees", "Integer");
        fields.put("AnnualRevenue", "Decimal");
        return SObjectClassGen.generate("Account", fields, java.util.Set.of());
    }

    @Test
    void badAssign_stringIntoNumber_failsCompile() throws Exception {
        String probe = "public class Probe { static void go() {"
            + " Account a = new Account(); a.setNumberOfEmployees(\"abc\"); } }";
        assertNotEquals(0, compile(account(), probe),
            "assigning a String to an Integer field must not compile");
    }

    @Test
    void wellTypedAssign_compiles() throws Exception {
        String probe = "public class Probe { static void go() {"
            + " Account a = new Account(); a.setNumberOfEmployees(10); a.setName(\"Acme\"); } }";
        assertEquals(0, compile(account(), probe),
            "well-typed assignments must compile");
    }

    @Test
    void unknownField_failsCompile() throws Exception {
        String probe = "public class Probe { static void go() {"
            + " Account a = new Account(); a.setStatuss(\"x\"); } }";
        assertNotEquals(0, compile(account(), probe),
            "an unknown field must not compile (cannot find symbol)");
    }
}
