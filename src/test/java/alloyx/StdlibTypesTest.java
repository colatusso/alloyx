package alloyx;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native callout/XML types (HttpRequest, Http, HttpResponse, Blob, EncodingUtil,
 * Dom.Document/XmlNode) are recognized so code using them type-checks instead of
 * collapsing onto the dynamic SObject — every method on a degraded SObject used to
 * read as "cannot find symbol". The behavior isn't implemented: calling one fails
 * clearly at runtime, never silently. Type accepted now, behavior later.
 */
class StdlibTypesTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void httpAndDomTypesCompile() throws Exception {
        Path p = probe("Caller", """
            public class Caller {
                public static void build() {
                    HttpRequest req = new HttpRequest();
                    req.setEndpoint('https://example.com');
                    req.setMethod('GET');
                    Http h = new Http();
                    HttpResponse res = h.send(req);
                    String body = res.getBody();
                    Dom.Document doc = new Dom.Document();
                    doc.load(body);
                    Dom.XmlNode root = doc.getRootElement();
                    String name = root.getName();
                }
            }
            """);
        Workspace.compile(List.of(p)); // compiles == types recognized (no javac failure)
    }

    @Test
    void blobAndEncodingUtilCompile() throws Exception {
        Path p = probe("Enc", """
            public class Enc {
                public static String go(String raw) {
                    Blob b = EncodingUtil.base64Decode(raw);
                    return EncodingUtil.base64Encode(b);
                }
            }
            """);
        Workspace.compile(List.of(p));
    }

    @Test
    void triggerContextIsRecognized() throws Exception {
        // Trigger.new is a Java keyword (was emitted verbatim -> "identifier expected");
        // Trigger.oldMap / Trigger.isInsert are context members. All must type-check.
        Path p = probe("Handler", """
            public class Handler {
                public static void go() {
                    List<Account> recs = (List<Account>) Trigger.new;
                    Map<Id, Account> prev = (Map<Id, Account>) Trigger.oldMap;
                    Boolean ins = Trigger.isInsert;
                }
            }
            """);
        Workspace.compile(List.of(p)); // compiles == trigger context recognized
    }

    @Test
    void callingNativeApiFailsClearlyAtRuntime() throws Exception {
        // recognized at compile time, but a callout doesn't run locally — it must
        // fail loudly (UnsupportedOperationException), not silently do nothing
        Path p = probe("Run", """
            public class Run {
                public static void go() {
                    HttpRequest req = new HttpRequest();
                    req.setEndpoint('x');
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Run");
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
            () -> c.getMethod("go").invoke(null));
        assertTrue(ex.getCause() instanceof UnsupportedOperationException,
            "expected UnsupportedOperationException, got " + ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("not available locally"),
            ex.getCause().getMessage());
    }
}
