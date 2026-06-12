// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Visualforce/Connect/Schema PLATFORM types are recognized so real controller and
 * integration code type-checks instead of flooding `check` with "package ... does not
 * exist" / "cannot find symbol". Pure-data types (PageReference url/params, ApexPages
 * Severity/Message) round-trip locally; org-coupled namespaces (ConnectApi, the static
 * Schema describe chain) type-check as Object and fail clearly only if invoked.
 */
class PlatformStubsTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void apexPagesSeverityAndMessageRoundTripLocally() throws Exception {
        // ApexPages.Severity.ERROR resolves; a Message stores its summary and reads it back.
        Path p = probe("Notifier", """
            public class Notifier {
                public static String build() {
                    ApexPages.Message m = new ApexPages.Message(ApexPages.Severity.ERROR, 'boom');
                    return m.getSummary();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Notifier");
        assertEquals("boom", c.getMethod("build").invoke(null));
    }

    @Test
    void pageReferenceUrlAndParametersRoundTrip() throws Exception {
        Path p = probe("Router", """
            public class Router {
                public static String go() {
                    PageReference ref = new PageReference('/apex/Home');
                    ref.getParameters().put('id', '001');
                    ref.setRedirect(true);
                    return ref.getUrl() + '?id=' + ref.getParameters().get('id');
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Router");
        assertEquals("/apex/Home?id=001", c.getMethod("go").invoke(null));
    }

    @Test
    void aggregateResultListCompilesAndReadsAlias() throws Exception {
        // a List<AggregateResult> with get('alias') type-checks; run uses a manually-built row.
        Path p = probe("Reporter", """
            public class Reporter {
                public static Object firstTotal(List<AggregateResult> rows) {
                    return rows.get(0).get('total');
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Reporter");
        alloyx.runtime.AggregateResult row = new alloyx.runtime.AggregateResult("total", 42);
        alloyx.runtime.List<alloyx.runtime.AggregateResult> rows = new alloyx.runtime.List<>();
        rows.add(row);
        assertEquals(42, c.getMethod("firstTotal", alloyx.runtime.List.class).invoke(null, rows));
    }

    @Test
    void connectApiReferenceTypeChecks() throws Exception {
        // ConnectApi.* references (nested type + a static call) must type-check (degrade to Object).
        Path p = probe("Social", """
            public class Social {
                public static void post(String communityId, String feedId) {
                    ConnectApi.FeedElement el =
                        ConnectApi.ChatterFeeds.postFeedElement(communityId, feedId);
                }
            }
            """);
        Workspace.compile(List.of(p)); // compiles == ConnectApi recognized
    }

    @Test
    void schemaAccessPatternsCompile() throws Exception {
        // Schema.getGlobalDescribe() and the Schema.SObjectType.<Name> describe chain type-check.
        Path p = probe("Describer", """
            public class Describer {
                public static void go() {
                    Map<String, Schema.SObjectType> all = Schema.getGlobalDescribe();
                    Object token = Schema.SObjectType.Account.fields.Name;
                }
            }
            """);
        Workspace.compile(List.of(p)); // compiles == Schema patterns recognized
    }
}
