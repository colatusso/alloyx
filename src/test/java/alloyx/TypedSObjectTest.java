package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof of typed-sObject validation: with a describe available (here a
 * fake gateway, no live org), the transpiler generates typed sObject classes and
 * javac then rejects what the Apex compiler rejects — a String into a Number field,
 * an unknown field — while the well-typed code compiles and runs. Mirrors the real
 * offline flow (`allx schema sync` populates the same cache the gateway feeds here).
 */
class TypedSObjectTest {

    @TempDir
    Path dir;

    /** Fake middleware that can describe Account (and answer a trivial query). */
    static final class DescribeGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            alloyx.runtime.List<SObject> out = new alloyx.runtime.List<>();
            out.add(new SObject("Account", "Id", "001", "Name", "Acme"));
            return out;
        }

        @Override
        public void insert(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void update(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void delete(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public Map<String, String> describe(String sobjectType) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("Id", "Id");
            f.put("Name", "String");
            f.put("Industry", "String");
            f.put("NumberOfEmployees", "Integer");
            f.put("AnnualRevenue", "Decimal");
            return f;
        }
    }

    @BeforeEach
    void connect() throws Exception {
        cleanSchemaCache();
        Database.setGateway(new DescribeGateway());
    }

    @AfterEach
    void disconnect() throws Exception {
        Database.setGateway(new UnconnectedGateway());
        cleanSchemaCache(); // don't leak a typed Account into other tests
    }

    private void cleanSchemaCache() throws Exception {
        Path schema = Workspace.CACHE_DIR.resolve("schema");
        if (Files.isDirectory(schema)) {
            try (var w = Files.walk(schema)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void typedSObject_compilesAndRuns() throws Exception {
        // a.NumberOfEmployees is an Integer field -> typed getter/setter, real arithmetic
        Path p = probe("Good", """
            public class Good {
                public static Integer grow() {
                    Account a = new Account(Name = 'Acme', NumberOfEmployees = 10);
                    a.NumberOfEmployees = a.NumberOfEmployees + 5;
                    return a.NumberOfEmployees;
                }
            }
            """);
        Class<?> good = Workspace.compile(List.of(p)).load("Good");
        Object result = good.getMethod("grow").invoke(null);
        assertEquals(Integer.valueOf(15), result);
    }

    @Test
    void stringIntoNumberField_failsCompile() throws Exception {
        Path p = probe("BadAssign", """
            public class BadAssign {
                public static void go() {
                    Account a = new Account();
                    a.NumberOfEmployees = 'abc';
                }
            }
            """);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> Workspace.compile(List.of(p)));
        assertTrue(ex.getMessage().contains("javac"), ex.getMessage());
    }

    @Test
    void unknownField_failsCompile() throws Exception {
        Path p = probe("Ghost", """
            public class Ghost {
                public static void go() {
                    Account a = new Account();
                    a.Statuss = 'x';
                }
            }
            """);
        assertThrows(RuntimeException.class, () -> Workspace.compile(List.of(p)));
    }

    @Test
    void offlineRun_withoutSchema_staysGeneric() throws Exception {
        // no describe available -> Account stays the dynamic SObject (the pre-typing path),
        // so primitives-only logic and untyped field access still run 100% offline
        Database.setGateway(new UnconnectedGateway());
        cleanSchemaCache();
        Path p = probe("Generic", """
            public class Generic {
                public static String go() {
                    Account a = new Account(Name = 'Acme');
                    a.NumberOfEmployees = 'whatever';
                    return (String) a.Name;
                }
            }
            """);
        Class<?> generic = Workspace.compile(List.of(p)).load("Generic");
        assertEquals("Acme", generic.getMethod("go").invoke(null));
    }
}
