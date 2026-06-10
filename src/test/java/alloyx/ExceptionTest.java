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
 * Every Apex Exception subclass implicitly has the built-in constructors (no-arg,
 * (String), ...), even when it declares a custom one — so `new MyErr('msg')` is
 * valid Apex regardless. The transpiler must inject the built-ins it didn't already
 * declare, instead of suppressing them as soon as any custom constructor exists
 * (the real-world miss: an exception declaring only an (HttpResponse) constructor).
 */
class ExceptionTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void customConstructorDoesNotSuppressBuiltInStringCtor() throws Exception {
        // MyErr declares only a (Integer) constructor; the built-in (String) one must
        // still exist (its own (Integer) ctor even delegates to it via this(...)).
        Files.writeString(dir.resolve("MyErr.cls"), """
            public class MyErr extends Exception {
                public MyErr(Integer code) { this('code ' + code); }
            }
            """);
        Path caller = probe("Caller", """
            public class Caller {
                public static String go() {
                    try { throw new MyErr('boom'); }
                    catch (MyErr e) { return e.getMessage(); }
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(dir.resolve("MyErr.cls"), caller)).load("Caller");
        assertEquals("boom", c.getMethod("go").invoke(null));
    }

    @Test
    void customConstructorDoesNotSuppressNoArgCtor() throws Exception {
        Files.writeString(dir.resolve("Oops.cls"), """
            public class Oops extends Exception {
                public Oops(Integer code) { this('e' + code); }
            }
            """);
        Path caller = probe("Maker", """
            public class Maker {
                public static String go() {
                    Oops e = new Oops();
                    return 'made';
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(dir.resolve("Oops.cls"), caller)).load("Maker");
        assertEquals("made", c.getMethod("go").invoke(null));
    }
}
