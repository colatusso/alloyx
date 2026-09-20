// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for syntax that must not be discarded as a clean parse. */
class ParserSyntaxTest {

    @TempDir
    Path dir;

    @Test
    void unknownCharacter_isReportedByCheckInsteadOfFalseClean() throws Exception {
        Path source = dir.resolve("Broken.cls");
        Files.writeString(source, "public class Broken {\n"
            + "    public Integer value() { return 1# + 2; }\n"
            + "}\n");

        List<Workspace.Diag> diags = Workspace.check(source, null, dir.resolve(".apexcache"));

        assertEquals(1, diags.size());
        assertEquals("ERROR", diags.get(0).severity());
        assertEquals(2, diags.get(0).line());
        assertTrue(diags.get(0).message().contains("'#'"), diags.toString());
    }

    @Test
    void autoProperty_getSet_isStillRepresentedAsAField() {
        ClassDecl cls = Parser.parse("public class Settings { String name { get; private set; } }");

        assertEquals(1, cls.fields().size());
        assertEquals("String", cls.fields().get(0).type());
        assertEquals("name", cls.fields().get(0).name());
    }

    @Test
    void autoProperty_singleAccessorForms_areStillAccepted() {
        ClassDecl cls = Parser.parse("public class Settings { String readOnly { get; } String writeOnly { set; } }");

        assertEquals(List.of("readOnly", "writeOnly"),
            cls.fields().stream().map(Field::name).toList());
    }

    @Test
    void propertyAccessorBody_isRejectedInsteadOfDiscarded() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> Parser.parse("""
            public class Settings {
                String name { get { return 'hidden'; } set; }
            }
            """));

        assertTrue(error.getMessage().contains("property accessor bodies"), error.getMessage());
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void staticInitializer_isRejectedInsteadOfDiscarded() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> Parser.parse("""
            public class Bootstrap {
                static { Integer value = 1; }
            }
            """));

        assertTrue(error.getMessage().contains("static initializer blocks"), error.getMessage());
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void staticInitializer_checkReportsUnsupportedBlock() throws Exception {
        Path source = dir.resolve("Bootstrap.cls");
        Files.writeString(source, "public class Bootstrap {\n"
            + "    static { Integer value = 1; }\n"
            + "}\n");

        List<Workspace.Diag> diags = Workspace.check(source, null, dir.resolve(".apexcache"));

        assertFalse(diags.isEmpty());
        assertTrue(diags.get(0).message().contains("static initializer blocks"), diags.toString());
    }

    @Test
    void trailingTopLevelDeclaration_isRejectedByParseAndCheck() throws Exception {
        String sourceText = "public class Bad {} public class Hidden {}";
        RuntimeException parseError = assertThrows(RuntimeException.class,
            () -> Parser.parse(sourceText));
        RuntimeException checkParseError = assertThrows(RuntimeException.class,
            () -> Parser.parseWithLines(sourceText));
        assertTrue(parseError.getMessage().contains("after top-level class"), parseError.getMessage());
        assertTrue(checkParseError.getMessage().contains("line 1"), checkParseError.getMessage());

        Path source = dir.resolve("Bad.cls");
        Files.writeString(source, sourceText);
        List<Workspace.Diag> diags = Workspace.check(source, null, dir.resolve(".apexcache"));

        assertFalse(diags.isEmpty(), "trailing top-level declaration must not be a clean check");
        assertTrue(diags.get(0).message().contains("after top-level class"), diags.toString());
    }

    @Test
    void upsertExternalIdOperand_isRejectedByParseAndCheck() throws Exception {
        String sourceText = "public class DmlProbe {\n"
            + "    public static void save(SObject row) { upsert row External_Id__c; }\n"
            + "}\n";
        RuntimeException parseError = assertThrows(RuntimeException.class,
            () -> Parser.parse(sourceText));
        assertTrue(parseError.getMessage().contains("extra tokens after upsert"),
            parseError.getMessage());
        assertTrue(parseError.getMessage().contains("line 2"), parseError.getMessage());

        Path source = dir.resolve("DmlProbe.cls");
        Files.writeString(source, sourceText);
        List<Workspace.Diag> diags = Workspace.check(source, null, dir.resolve(".apexcache"));

        assertFalse(diags.isEmpty(), "unsupported upsert operand must not be a clean check");
        assertTrue(diags.get(0).message().contains("extra tokens after upsert"), diags.toString());
    }

    @Test
    void upsertSingleRecordWithoutExternalId_remainsSupported() {
        ClassDecl cls = Parser.parse("public class DmlProbe {"
            + " public static void save(SObject row) { upsert row; } }");

        assertEquals(1, cls.methods().size());
        assertTrue(cls.methods().get(0).body().get(0) instanceof Dml);
    }
}
