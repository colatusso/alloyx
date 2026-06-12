// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-class member typing: the transpiler builds the member-type index over EVERY class in the
 * compile set (Workspace.memberTypes), so the central typer can resolve a field/method/param that
 * lives on ANOTHER class — not just the one being emitted. Before this, a class was indexed only
 * while it was the one being transpiled, leaving the typer blind to sibling members and emitting
 * Java that didn't compile (e.g. raw `rec.config.Endpoint__c` instead of a typed getter).
 *
 * <p>All identifiers here are invented (Config__c / Endpoint__c / EventRecord / Sender / Cart /
 * Wallet / ...) — pure fixtures, not from any real schema.
 */
class CrossClassMemberTypeTest {

    @TempDir
    Path dir;

    /** Fake middleware that describes the invented Config__c sObject. */
    static final class DescribeGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            return new alloyx.runtime.List<>();
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
            // only Config__c is a real sObject here; anything else -> not described
            if (!sobjectType.equalsIgnoreCase("Config__c")) {
                return null;
            }
            Map<String, String> f = new LinkedHashMap<>();
            f.put("Id", "Id");
            f.put("Name", "String");
            f.put("Endpoint__c", "String");
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
        cleanSchemaCache();
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
    void readsSObjectFieldOnAnotherClassField_usesTypedGetter() throws Exception {
        // EventRecord.config is a Config__c (typed sObject) field; Sender reads
        // rec.config.Endpoint__c. The typer must resolve config's type cross-class so the
        // final hop emits the typed getter (getEndpoint__c()), not a raw `.Endpoint__c`.
        // The whole build runs in Apex so we never touch a package-private generated field.
        Path rec = probe("EventRecord", """
            public class EventRecord {
                public Config__c config;
            }
            """);
        Path sender = probe("Sender", """
            public class Sender {
                public static String endpointOf() {
                    EventRecord rec = new EventRecord();
                    rec.config = new Config__c(Endpoint__c = 'https://x');
                    return rec.config.Endpoint__c;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(rec, sender));
        Object result = c.load("Sender").getMethod("endpointOf").invoke(null);
        assertEquals("https://x", result);
    }

    @Test
    void writesSObjectFieldOnAnotherClassField_usesTypedSetter() throws Exception {
        // write side: Sender does rec.config.Endpoint__c = 'v' — must route through the
        // typed setter on Config__c (cross-class chain), not a raw field assignment.
        Path rec = probe("EventRecord", """
            public class EventRecord {
                public Config__c config;
            }
            """);
        Path sender = probe("Sender", """
            public class Sender {
                public static String setEndpoint(String v) {
                    EventRecord rec = new EventRecord();
                    rec.config = new Config__c();
                    rec.config.Endpoint__c = v;
                    return rec.config.Endpoint__c;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(rec, sender));
        Object result = c.load("Sender").getMethod("setEndpoint", String.class).invoke(null, "https://y");
        assertEquals("https://y", result);
    }
}
