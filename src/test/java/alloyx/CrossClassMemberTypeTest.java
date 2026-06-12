// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.SchemaProvider;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            // Config__c is a custom sObject; Event is a real STANDARD sObject — its presence here lets
            // the inner-vs-sObject precedence tests prove a class WITHOUT an inner Event still routes a
            // bare Event-typed value as the std sObject, while a colliding inner shadows it per-class.
            if (sobjectType.equalsIgnoreCase("Config__c")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                f.put("Endpoint__c", "String");
                return f;
            }
            if (sobjectType.equalsIgnoreCase("Event")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Subject", "String");
                return f;
            }
            return null; // anything else -> not described
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

    // --- inner class shadowing a same-named standard sObject (Event) ---
    //
    // A member declared with an INNER class type whose simple name collides with a standard sObject
    // (Event) used to be re-interpreted as the std sObject when its type was resolved cross-class,
    // routing the next hop through the dynamic SObject path (`.getdata()` — a method that doesn't
    // exist on the generated Event). Apex scoping says the inner type wins inside its outer class for
    // member declarations, so `req.event` must type as CallbackRequest.Event (the inner), and the
    // chain `req.event.data.status` reads plain Java fields. Event / Data are neutral fixtures that
    // happen to collide with real standard sObject names — the resolution logic stays name-agnostic.

    /** The DTO whose field `event` is typed by an inner class colliding with the std Event sObject. */
    private Path callbackRequest() throws Exception {
        return probe("CallbackRequest", """
            public class CallbackRequest {
                public Event event;
                public class Event { public Data data; }
                public class Data  { public String status; }
            }
            """);
    }

    @Test
    void innerTypeShadowingStdSObject_crossClassChainReadsThrough() throws Exception {
        // ReceiptMapper (a DIFFERENT class) reads req.event.data.status. event is typed by the inner
        // CallbackRequest.Event, not the std Event sObject, so the whole chain is plain field access.
        Path cb = callbackRequest();
        Path mapper = probe("ReceiptMapper", """
            public class ReceiptMapper {
                public static String read() {
                    CallbackRequest req = new CallbackRequest();
                    req.event = new CallbackRequest.Event();
                    req.event.data = new CallbackRequest.Data();
                    req.event.data.status = 'ok';
                    return req.event.data.status;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(cb, mapper));
        assertEquals("ok", c.load("ReceiptMapper").getMethod("read").invoke(null));
    }

    @Test
    void innerTypeShadowingStdSObject_sameClassChainReadsThrough() throws Exception {
        // The same shadowing must hold for SAME-class access (this.event.data.status inside the outer
        // that owns the inner), not just cross-class — both resolve through the qualified inner entry.
        Path cb = probe("CallbackRequest", """
            public class CallbackRequest {
                public Event event;
                public class Event { public Data data; }
                public class Data  { public String status; }
                public String readOwn() {
                    this.event = new Event();
                    this.event.data = new Data();
                    this.event.data.status = 'self';
                    return this.event.data.status;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(cb));
        Class<?> k = c.load("CallbackRequest");
        Object inst = k.getDeclaredConstructor().newInstance();
        assertEquals("self", k.getMethod("readOwn").invoke(inst));
    }

    @Test
    void innerTypeShadowingStdSObject_throughMethodReturn_crossClass() throws Exception {
        // The chain through a METHOD return typed by the inner class: CallbackRequest.makeEvent()
        // returns Event (the inner), consumed cross-class as `req.makeEvent().data.status`.
        Path cb = probe("CallbackRequest", """
            public class CallbackRequest {
                public class Event { public Data data; }
                public class Data  { public String status; }
                public Event makeEvent() {
                    Event e = new Event();
                    e.data = new Data();
                    e.data.status = 'made';
                    return e;
                }
            }
            """);
        Path mapper = probe("ReceiptMapper", """
            public class ReceiptMapper {
                public static String read() {
                    CallbackRequest req = new CallbackRequest();
                    return req.makeEvent().data.status;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(cb, mapper));
        assertEquals("made", c.load("ReceiptMapper").getMethod("read").invoke(null));
    }

    // --- precedence preserved: no inner Event => bare Event stays the std sObject ---

    private static final SchemaProvider EVENT_SCHEMA = new SchemaProvider() {
        @Override
        public String fieldType(String sobjectType, String fieldName) {
            if (!sobjectType.equalsIgnoreCase("Event")) {
                return null;
            }
            return fieldName.equalsIgnoreCase("Subject") ? "String"
                : fieldName.equalsIgnoreCase("Id") ? "Id" : null;
        }

        @Override
        public boolean isDescribed(String sobjectType) {
            return sobjectType.equalsIgnoreCase("Event");
        }

        @Override
        public String canonicalField(String sobjectType, String fieldName) {
            return fieldName.equalsIgnoreCase("Subject") ? "Subject" : fieldName;
        }
    };

    @Test
    void noCollidingInner_bareEventFieldStillTypesAsStdSObject() throws Exception {
        // A class WITHOUT an inner Event: a field typed bare `Event` must STILL resolve to the std
        // sObject (the fix is per-owner, never a global remap). Proven by the emission shape: reading
        // host.evt.Subject through the field routes via the typed getter getSubject(), which only
        // fires when evt is recognized as the std sObject. (host.evt is a cross-class user-field hop.)
        ClassDecl holder = Parser.parse("""
            public class Holder {
                public Event evt;
            }
            """);
        ClassDecl reader = Parser.parse("""
            public class Reader {
                public static String go(Holder host) {
                    return host.evt.Subject;
                }
            }
            """);
        List<ClassDecl> all = List.of(holder, reader);
        String src = Transpiler.transpile(reader, Set.of("Holder", "Reader"), EVENT_SCHEMA,
            Set.of("Event"), Workspace.memberIndex(all), Workspace.memberTypes(all)).source();
        assertTrue(src.contains("getSubject()"), src);      // typed std-sObject getter -> still sObject
        assertFalse(src.contains(".Subject"), src);          // not a raw user-field access
    }
}
