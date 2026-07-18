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
 * A library written in Apex is ordinary workspace source. Its full transitive
 * dependency graph must resolve without naming or special-casing that library.
 */
class WorkspaceDependencyClosureTest {

    @TempDir
    Path dir;

    private Path write(String name, String source) throws Exception {
        Path file = dir.resolve(name + ".cls");
        Files.writeString(file, source);
        return file;
    }

    private List<String> names(List<Path> files) {
        return files.stream()
            .map(file -> file.getFileName().toString().replaceFirst("\\.cls$", ""))
            .toList();
    }

    @Test
    void resolvesAndCompilesAGenericApexLibraryTransitively() throws Exception {
        write("Payload", """
            public class Payload {
                public String value;
            }
            """);
        write("PayloadFactory", """
            public class PayloadFactory {
                public static Payload create() {
                    Payload payload = new Payload();
                    payload.value = 'ok';
                    return payload;
                }
            }
            """);
        write("LibraryFacade", """
            public class LibraryFacade {
                public String read() {
                    return PayloadFactory.create().value;
                }
            }
            """);
        Path endpoint = write("Endpoint", """
            public class Endpoint {
                public String run() {
                    return new LibraryFacade().read();
                }
            }
            """);
        write("Unrelated", """
            public class Unrelated {
                public Integer broken() { return 'not an integer'; }
            }
            """);

        List<Path> closure = Workspace.resolveDeps(endpoint);
        assertEquals(List.of("Endpoint", "LibraryFacade", "PayloadFactory", "Payload"), names(closure));

        Workspace.Compiled compiled = Workspace.compile(closure, List.of(), dir.resolve(".apexcache"));
        Object endpointInstance = compiled.load("Endpoint").getConstructor().newInstance();
        assertEquals("ok", compiled.load("Endpoint").getMethod("run").invoke(endpointInstance));
        assertEquals(List.of(), Workspace.check(endpoint, null, dir.resolve(".apexcache")),
            "a broken class outside the dependency closure must not affect the open file");
    }

    @Test
    void checkTypeChecksThroughTheFullGenericLibraryClosure() throws Exception {
        write("Payload", """
            public class Payload {
                public String value;
            }
            """);
        write("PayloadFactory", """
            public class PayloadFactory {
                public static Payload create() {
                    return new Payload();
                }
            }
            """);
        write("LibraryFacade", """
            public class LibraryFacade {
                public Payload read() {
                    return PayloadFactory.create();
                }
            }
            """);
        Path endpoint = write("Endpoint", """
            public class Endpoint {
                public Integer run() {
                    return 1;
                }
            }
            """);
        String unsavedSource = """
            public class Endpoint {
                public Integer run() {
                    return new LibraryFacade().read().value;
                }
            }
            """;

        List<Workspace.Diag> diags = Workspace.check(
            endpoint, unsavedSource, dir.resolve(".apexcache"));
        assertTrue(diags.stream().anyMatch(diag -> diag.message().contains("incompatible types")),
            "the target's String-to-Integer error requires the full library closure: " + diags);
    }
}
