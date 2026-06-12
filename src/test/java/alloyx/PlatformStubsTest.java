// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.SchemaProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void connectApiVariableMemberAccess_roundTripsLocally() throws Exception {
        // Fix B regression: a VARIABLE of a ConnectApi nested type is a dynamic SObject, so
        // member writes/reads route through .put()/.get() and round-trip locally (the pre-stub
        // behavior). Mapping ConnectApi.* to Object had killed member access on such variables.
        Path p = probe("Pager", """
            public class Pager {
                public static Integer go() {
                    ConnectApi.SomeInput input = new ConnectApi.SomeInput();
                    input.page = 5;
                    input.pageSize = 10;
                    return input.page;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Pager");
        assertEquals(Integer.valueOf(5), c.getMethod("go").invoke(null));
    }

    @Test
    void connectApiStaticChain_stillDegradesToUnsupported() throws Exception {
        // Fix B must keep the STATIC chain degradation: a call rooted at the bare ConnectApi
        // namespace can't be an SObject value, so it still emits the unsupported placeholder.
        String java = transpile("""
            public class Caller {
                public static void go(String a, String b) {
                    ConnectApi.ChatterFeeds.postFeedElement(a, b);
                }
            }
            """);
        assertTrue(java.contains("ConnectApi.unsupported(\"ChatterFeeds.postFeedElement\")"), java);
        // the static call is the placeholder, never a bare (unresolvable) ConnectApi.X.y(...) call
        assertFalse(java.contains("ConnectApi.ChatterFeeds.postFeedElement("), java);
    }

    // transpile a single class with no described schema (the static-chain shape needs no org).
    private static String transpile(String src) {
        ClassDecl cls = Parser.parse(src);
        SchemaProvider noSchema = new SchemaProvider() {
            @Override public String fieldType(String s, String f) { return null; }
            @Override public boolean isDescribed(String s) { return false; }
            @Override public String canonicalField(String s, String f) { return f; }
            @Override public Map<String, String> fields(String s) { return null; }
        };
        return Transpiler.transpile(cls, Set.of(cls.name()), noSchema, Set.of()).source();
    }

    @Test
    void assertClassRoutesToRuntime_caseInsensitive() throws Exception {
        // The modern Apex Assert class must route as a static-call target onto the runtime Assert.
        // Apex is case-insensitive, so `assert.areEqual(...)` (lowercase) must fold to Assert too —
        // `assert` is a Java keyword, so a literal pass-through wouldn't even compile.
        String java = transpile("""
            public class Checks {
                public static void go() {
                    Assert.areEqual(1, 1);
                    assert.areEqual(2, 2);
                    Assert.isTrue(true);
                }
            }
            """);
        assertTrue(java.contains("Assert.areEqual(1, 1)"), java);
        assertTrue(java.contains("Assert.areEqual(2, 2)"), java);   // assert.areEqual folded to Assert
        assertTrue(java.contains("Assert.isTrue(true)"), java);
        assertFalse(java.contains("assert.areEqual"), java);        // never the bare keyword form
    }

    @Test
    void assertClassRunsEndToEnd() throws Exception {
        // a full compile+run: the runtime Assert links and its checks behave. Apex CANNOT catch
        // an assertion failure (it is fatal on the platform), so the probe's catch (Exception)
        // must NOT swallow it — the failure surfaces to the JAVA caller instead. Integer-vs-
        // Decimal reconciliation is exercised on the passing checks.
        Path p = probe("AssertUser", """
            public class AssertUser {
                public static String run() {
                    Assert.areEqual(3, 3);
                    Decimal d = 3;
                    Assert.areEqual(3, d);
                    try { Assert.areEqual(1, 2); return 'NO-THROW'; }
                    catch (Exception e) { return 'caught-but-should-not'; }
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("AssertUser");
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> c.getMethod("run").invoke(null));
        assertTrue(thrown.getCause() instanceof alloyx.runtime.AssertException,
            "assert failure must escape the Apex catch (fatal on platform), got " + thrown.getCause());
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
