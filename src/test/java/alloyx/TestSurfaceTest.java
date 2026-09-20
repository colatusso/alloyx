// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.ApexPages;
import alloyx.runtime.Datetime;
import alloyx.runtime.PageReference;
import alloyx.runtime.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extra Test-namespace surface fixture code leans on. {@code getStandardPricebookId()} hands
 * back a deterministic synthetic Id (no org locally, so a stable fake lets fixtures proceed);
 * {@code setCurrentPage(PageReference)} wires the same ApexPages current-page state {@code
 * currentPage()} reads; {@code setCreatedDate(Id, Datetime)} mutates an audit field, which
 * requires an org, so it degrades clearly.
 */
class TestSurfaceTest {
    @TempDir
    java.nio.file.Path dir;


    @BeforeEach
    @AfterEach
    void resetCurrentPage() {
        ApexPages.setCurrentPage(null); // isolate the process-static current-page between runs
    }

    @org.junit.jupiter.api.Test
    void standardPricebookIdIsDeterministicAndIdShaped() {
        String id = Test.getStandardPricebookId();
        assertNotNull(id);
        // a standard 18-char Salesforce Id shape, so fixture code that stores/compares it proceeds
        assertEquals(18, id.length());
        // deterministic: the same value every call (a stable fake, not a random one)
        assertEquals(id, Test.getStandardPricebookId());
    }

    @org.junit.jupiter.api.Test
    void setCurrentPageWiresApexPagesCurrentPage() {
        PageReference pr = new PageReference("/apex/Demo");
        Test.setCurrentPage(pr);
        // ApexPages.currentPage() must hand back the SAME reference set via Test.setCurrentPage
        assertSame(pr, ApexPages.currentPage());
    }

    @org.junit.jupiter.api.Test
    void setCreatedDateDegradesClearly() {
        // mutating an audit field needs an org transaction — no local equivalent
        assertThrows(UnsupportedOperationException.class,
            () -> Test.setCreatedDate("001000000000001", Datetime.now()));
    }

    @org.junit.jupiter.api.Test
    void isRunningTestIsScopedToTheLocalRunnerContext() throws Exception {
        assertEquals(false, Test.isRunningTest());
        Test.runLocalTest(() -> {
            assertEquals(true, Test.isRunningTest());
            return null;
        });
        assertEquals(false, Test.isRunningTest());
    }

    @org.junit.jupiter.api.Test
    void apexCannotCallTheLocalTestExitHook() throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve(".apexcache"));
        java.nio.file.Path f = dir.resolve("Bypass.cls");
        java.nio.file.Files.writeString(f, """
            public class Bypass {
                public static void go() {
                    Test.exitLocalTest();
                    Database.insert(new SObject('Account'));
                }
            }
            """);

        java.util.List<Workspace.Diag> diags = Workspace.check(
            f, null, dir.resolve(".apexcache"));
        assertTrue(diags.stream().anyMatch(d -> d.message().contains("exitLocalTest")),
            diags.toString());
    }

    @org.junit.jupiter.api.Test
    void transpiledApexReachesTheNewTestSurface() throws Exception {
        // end-to-end: an Apex fixture uses all three (case-insensitively, as Apex allows) and the
        // transpiler folds each static call to the runtime Test method — getStandardPricebookId
        // returns the synthetic Id, setCurrentPage seeds ApexPages.currentPage().
        java.nio.file.Path f = java.nio.file.Files.createTempFile("Fixture", ".cls");
        java.nio.file.Files.writeString(f, """
            public class Fixture {
                public static String go() {
                    String pbId = Test.getStandardPricebookId();
                    Test.setcurrentpage(new PageReference('/apex/X'));
                    return pbId + '|' + ApexPages.currentPage().getUrl();
                }
            }
            """);
        Class<?> k = Workspace.compile(java.util.List.of(f)).load("Fixture");
        assertEquals("01s000000000000AAA|/apex/X", k.getMethod("go").invoke(null));
    }
}
