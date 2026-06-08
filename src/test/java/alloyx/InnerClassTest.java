package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inner classes and Apex's case-insensitivity are language semantics, not org
 * specifics. Proven end-to-end (parse -> javac -> run):
 *  - an inner class emits as a real nested type, so member access is a field, not
 *    a dynamic sObject get (a get returns Object and would break `3 * b.value`);
 *  - an inner class sees the outer class's static fields;
 *  - a reference differing only in case still binds to its declaration;
 *  - a native enum member (LoggingLevel.Info) folds to the runtime constant.
 */
class InnerClassTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void innerClassMemberAccessIsFieldNotSObjectGet() throws Exception {
        Path p = probe("Holder", """
            public class Holder {
                class Box {
                    public Integer value;
                    public Box(Integer v) { this.value = v; }
                }
                public static Integer total() {
                    List<Box> boxes = new List<Box>();
                    boxes.add(new Box(2));
                    boxes.add(new Box(5));
                    Integer sum = 0;
                    for (Holder.Box b : boxes) {
                        sum = sum + 3 * b.value;
                    }
                    return sum;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Holder");
        assertEquals(Integer.valueOf(21), c.getMethod("total").invoke(null)); // 3*2 + 3*5
    }

    @Test
    void innerClassReadsOuterStaticField() throws Exception {
        Path p = probe("Counter", """
            public class Counter {
                private static Integer base = 100;
                class Tick {
                    public Integer at;
                    public Tick() { this.at = base; }
                }
                public static Integer make() {
                    Tick t = new Tick();
                    return t.at;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Counter");
        assertEquals(Integer.valueOf(100), c.getMethod("make").invoke(null));
    }

    @Test
    void caseInsensitiveFieldReferenceBinds() throws Exception {
        // declared `stackTrace`, used `stacktrace` — Apex treats them as one symbol
        Path p = probe("Trace", """
            public class Trace {
                public static List<String> stackTrace = new List<String>();
                public static Integer go() {
                    stacktrace.add('a');
                    stackTrace.add('b');
                    return stackTrace.size();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Trace");
        assertEquals(Integer.valueOf(2), c.getMethod("go").invoke(null));
    }

    @Test
    void nativeEnumMemberCaseInsensitiveCompilesAndRuns() throws Exception {
        // LoggingLevel.Info must fold to the runtime constant INFO
        Path p = probe("Log", """
            public class Log {
                public static void go() {
                    System.debug(LoggingLevel.Info, 'hello');
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Log");
        c.getMethod("go").invoke(null); // no throw == compiled + ran
    }
}
