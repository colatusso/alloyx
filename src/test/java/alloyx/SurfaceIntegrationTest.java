// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end: Apex source using the new Database/System/ApexPages surface compiles and runs. */
class SurfaceIntegrationTest {
    @TempDir Path dir;
    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void leadConvertAndSavepointAndExecuteBatch_compileAndDegradeHonestly() throws Exception {
        Path p = probe("Ops", """
            public class Ops {
                public static String buildLead() {
                    Database.LeadConvert lc = new Database.LeadConvert();
                    lc.setLeadId('00Q1');
                    lc.setConvertedStatus('Closed - Converted');
                    return lc.getLeadId() + '|' + lc.getConvertedStatus();
                }
                public static Object savepoint() { return Database.setSavepoint(); }
                public static void runBatch() { Database.executeBatch(new Object(), 200); }
                public static String schedule() {
                    return System.schedule('j', '0 0 1 * * ?', new Object());
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Ops");
        assertEquals("00Q1|Closed - Converted", c.getMethod("buildLead").invoke(null));
        // savepoint() returns an opaque token (compiles + runs)
        c.getMethod("savepoint").invoke(null);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> c.getMethod("runBatch").invoke(null));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> c.getMethod("schedule").invoke(null));
    }

    @Test
    void apexPagesController_compilesAndRoundTrips() throws Exception {
        Path p = probe("Ctrl", """
            public class Ctrl {
                public static String addAndRead() {
                    ApexPages.addMessage(new ApexPages.Message(ApexPages.Severity.ERROR, 'oops'));
                    return ApexPages.getMessages().get(0).getSummary();
                }
                public static Boolean current() {
                    PageReference pr = ApexPages.currentPage();
                    return pr != null;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Ctrl");
        alloyx.runtime.ApexPages.clearMessages();
        assertEquals("oops", c.getMethod("addAndRead").invoke(null));
        assertEquals(Boolean.TRUE, c.getMethod("current").invoke(null));
        alloyx.runtime.ApexPages.clearMessages();
    }
}
