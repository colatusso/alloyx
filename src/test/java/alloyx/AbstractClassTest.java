// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Apex `abstract` is a language semantic, not an org specific. An abstract method must
 * emit as a bodyless `abstract <ret> name();` (an empty concrete body is "missing return
 * statement" for a non-void return), and the class must be declared `abstract` so it can
 * hold one. Proven end-to-end (parse -> javac -> run): a concrete subclass overriding the
 * abstract method runs. Interfaces (also bodyless) are unaffected.
 */
class AbstractClassTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void abstractMethodWithNonVoidReturnTranspilesAndSubclassRuns() throws Exception {
        // abstract base with a non-void abstract method (the case that used to emit an empty
        // body and fail javac), plus a concrete subclass overriding it — runs end to end.
        Path base = probe("Shaper", """
            public abstract class Shaper {
                public abstract Object transform(Object input);
                public Object run(Object input) { return transform(input); }
            }
            """);
        Path sub = probe("Doubler", """
            public class Doubler extends Shaper {
                public override Object transform(Object input) {
                    Integer n = (Integer) input;
                    return n * 2;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(base, sub)).load("Doubler");
        Object out = c.getDeclaredConstructor().newInstance();
        assertEquals(Integer.valueOf(14), c.getMethod("run", Object.class).invoke(out, 7));
    }

    @Test
    void abstractClassWithoutAbstractMethodsStillEmitsAbstract() throws Exception {
        // an abstract class with only concrete methods is still `abstract` (can't be `new`ed),
        // and a concrete subclass instantiates and runs.
        Path base = probe("Calculator", """
            public abstract class Calculator {
                public virtual Integer base() { return 10; }
            }
            """);
        Path sub = probe("PlusFive", """
            public class PlusFive extends Calculator {
                public Integer total() { return base() + 5; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(base, sub)).load("PlusFive");
        Object out = c.getDeclaredConstructor().newInstance();
        assertEquals(Integer.valueOf(15), c.getMethod("total").invoke(out));
    }

    @Test
    void interfaceWithBodylessMethodIsUnaffected() throws Exception {
        // a bodyless method in an interface keeps emitting as an interface signature (no
        // `abstract` keyword needed, no body) and an implementor runs.
        Path iface = probe("Transformer", """
            public interface Transformer {
                Object transform(Object input);
            }
            """);
        Path impl = probe("Identity", """
            public class Identity implements Transformer {
                public Object transform(Object input) { return input; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(iface, impl)).load("Identity");
        Object out = c.getDeclaredConstructor().newInstance();
        assertEquals("x", c.getMethod("transform", Object.class).invoke(out, "x"));
    }
}
