package alloyx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor `check` compiles the open class together with its DIRECT dependencies
 * (its superclass and the classes used in its body), so inherited members and
 * external types resolve instead of surfacing as bogus "cannot find symbol". It
 * also flags a double-quoted string clearly (Apex uses single quotes) rather than
 * letting the bare digits inside lex as a number and blow up downstream.
 */
class CheckDepsTest {

    @TempDir
    Path dir;

    private Path write(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private boolean hasMissingSymbol(List<Workspace.Diag> diags) {
        return diags.stream().anyMatch(d -> d.message().contains("cannot find symbol"));
    }

    @Test
    void inheritedMembersResolveViaDirectDep() throws Exception {
        write("Base", """
            public virtual class Base {
                protected String name;
                protected String greet() { return 'hi ' + name; }
            }
            """);
        Path sub = write("Sub", """
            public class Sub extends Base {
                public String go() {
                    this.name = 'x';
                    return greet();
                }
            }
            """);
        List<Workspace.Diag> diags = Workspace.check(sub);
        assertFalse(hasMissingSymbol(diags),
            "inherited name/greet must resolve via the compiled superclass: " + diags);
    }

    @Test
    void innerClassOfAnotherClassResolves() throws Exception {
        write("Proxy", """
            public class Proxy {
                public class Response {
                    public String status;
                }
                public Response make() { return new Response(); }
            }
            """);
        Path user = write("User", """
            public class User {
                public String go() {
                    Proxy.Response r = new Proxy().make();
                    return r.status;
                }
            }
            """);
        List<Workspace.Diag> diags = Workspace.check(user);
        assertFalse(hasMissingSymbol(diags),
            "Proxy.Response (inner class of another class) must resolve: " + diags);
    }

    @Test
    void doubleQuotedStringReportedClearly() throws Exception {
        Path p = write("Dq", """
            public class Dq {
                public static void go() {
                    String s = "double";
                }
            }
            """);
        List<Workspace.Diag> diags = Workspace.check(p);
        assertTrue(diags.stream().anyMatch(d -> d.message().contains("single quotes")),
            "double quotes must be flagged with a clear message: " + diags);
    }
}
