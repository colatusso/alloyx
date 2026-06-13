// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RC3 runtime-stub families: XmlStreamWriter, Messaging, the REST surface, JSONGenerator and the
 * Schema describe family. The local-behaving ones (XML/JSON building, email/Rest data carriers)
 * transpile, compile AND run with a faithful result; the org-coupled members (sendEmail, picklist
 * values, record types) compile but degrade clearly. Each probe is Apex source the corpus shape
 * exercises — these used to collapse onto the dynamic SObject ("cannot find symbol").
 */
class RuntimeStubFamiliesTest {
    @TempDir Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    // --- XmlStreamWriter (local: builds well-formed XML) -----------------------------------------

    @Test
    void xmlStreamWriter_buildsWellFormedXml() throws Exception {
        Path p = probe("XmlBuilder", """
            public class XmlBuilder {
                public static String build() {
                    XmlStreamWriter w = new XmlStreamWriter();
                    w.writeStartDocument('UTF-8', '1.0');
                    w.writeStartElement(null, 'Order', null);
                    w.writeStartElement(null, 'Id', null);
                    w.writeCharacters('A & B');
                    w.writeEndElement();
                    w.writeEmptyElement(null, 'Flag', null);
                    w.writeEndElement();
                    w.writeEndDocument();
                    String xml = w.getXmlString();
                    w.close();
                    return xml;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("XmlBuilder");
        String xml = (String) c.getMethod("build").invoke(null);
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Order><Id>A &amp; B</Id><Flag/></Order>",
            xml);
    }

    @Test
    void xmlStreamWriter_namespacesAndAttributes() throws Exception {
        Path p = probe("XmlNs", """
            public class XmlNs {
                public static String build() {
                    XmlStreamWriter w = new XmlStreamWriter();
                    w.writeStartElement('env', 'Envelope', 'http://x');
                    w.writeNamespace('env', 'http://x');
                    w.writeAttribute(null, null, 'id', '7');
                    w.writeCharacters('hi');
                    w.writeEndElement();
                    return w.getXmlString();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("XmlNs");
        assertEquals(
            "<env:Envelope xmlns:env=\"http://x\" id=\"7\">hi</env:Envelope>",
            c.getMethod("build").invoke(null));
    }

    // --- Messaging (local: compose; org-coupled: send) -------------------------------------------

    @Test
    void messaging_singleEmail_roundTripsThenSendDegrades() throws Exception {
        Path p = probe("Mailer", """
            public class Mailer {
                public static String compose() {
                    Messaging.SingleEmailMessage mail = new Messaging.SingleEmailMessage();
                    mail.setToAddresses(new List<String>{ 'a@x.com', 'b@x.com' });
                    mail.setSubject('Hello');
                    mail.setPlainTextBody('Body');
                    Messaging.EmailFileAttachment att = new Messaging.EmailFileAttachment();
                    att.setFileName('f.txt');
                    att.setContentType('text/plain');
                    mail.setFileAttachments(new List<Messaging.EmailFileAttachment>{ att });
                    return mail.getSubject() + '|' + mail.getToAddresses().size()
                        + '|' + mail.getFileAttachments().get(0).getFileName();
                }
                public static void send() {
                    Messaging.SingleEmailMessage mail = new Messaging.SingleEmailMessage();
                    Messaging.sendEmail(new List<Messaging.SingleEmailMessage>{ mail });
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Mailer");
        assertEquals("Hello|2|f.txt", c.getMethod("compose").invoke(null));
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
            () -> c.getMethod("send").invoke(null));
        assertTrue(ex.getCause() instanceof UnsupportedOperationException, "" + ex.getCause());
    }

    // --- REST surface (local: inject + read request/response) ------------------------------------

    @Test
    void restContext_injectRequestReadResponse() throws Exception {
        // A resource reads RestContext.request and writes RestContext.response — exactly how an
        // @RestResource method works. The test injects the request and reads the response back.
        Path p = probe("Resource", """
            public class Resource {
                public static void handle() {
                    RestRequest req = RestContext.request;
                    RestResponse res = RestContext.response;
                    String who = req.params.get('name');
                    res.statusCode = 200;
                    res.responseBody = Blob.valueOf('hi ' + who);
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Resource");
        // inject the request/response on the runtime RestContext, then drive the resource
        Class<?> ctx = c.getClassLoader().loadClass("alloyx.runtime.RestContext");
        Class<?> reqC = c.getClassLoader().loadClass("alloyx.runtime.RestRequest");
        Class<?> resC = c.getClassLoader().loadClass("alloyx.runtime.RestResponse");
        Object req = reqC.getConstructor().newInstance();
        Object res = resC.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> params =
            (java.util.Map<String, String>) reqC.getField("params").get(req);
        params.put("name", "world");
        ctx.getField("request").set(null, req);
        ctx.getField("response").set(null, res);

        c.getMethod("handle").invoke(null);

        assertEquals(200, resC.getField("statusCode").get(res));
        Object body = resC.getField("responseBody").get(res);
        assertEquals("hi world", body.toString());
    }

    // --- JSONGenerator (local: builds JSON) ------------------------------------------------------

    @Test
    void jsonGenerator_buildsCompactJson() throws Exception {
        Path p = probe("JsonBuild", """
            public class JsonBuild {
                public static String build() {
                    JSONGenerator gen = JSON.createGenerator(false);
                    gen.writeStartObject();
                    gen.writeStringField('name', 'Acme');
                    gen.writeNumberField('qty', 5);
                    gen.writeFieldName('tags');
                    gen.writeStartArray();
                    gen.writeString('a');
                    gen.writeString('b');
                    gen.writeEndArray();
                    gen.writeBooleanField('active', true);
                    gen.writeEndObject();
                    return gen.getAsString();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("JsonBuild");
        assertEquals(
            "{\"name\":\"Acme\",\"qty\":5,\"tags\":[\"a\",\"b\"],\"active\":true}",
            c.getMethod("build").invoke(null));
    }

    // --- Schema describe family ------------------------------------------------------------------

    @Test
    void describeTokens_compileAndDegradeForOrgMetadata() throws Exception {
        // The describe tokens (PicklistEntry/RecordTypeInfo/ChildRelationship) and the describe-result
        // accessors that produce them now type-check; the org-only members degrade clearly when run.
        Path p = probe("Describer", """
            public class Describer {
                public static String picklistLabel(Schema.PicklistEntry pe) { return pe.getLabel(); }
                public static String recordType(Schema.RecordTypeInfo rti) { return rti.getRecordTypeId(); }
                public static String rel(Schema.ChildRelationship cr) { return cr.getRelationshipName(); }
                public static List<Schema.ChildRelationship> children(Schema.DescribeSObjectResult d) {
                    return d.getChildRelationships();
                }
                public static List<Schema.RecordTypeInfo> rtis(Schema.DescribeSObjectResult d) {
                    return d.getRecordTypeInfos();
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Describer");
        // every described accessor recognized (compiles); reflection confirms the methods exist
        assertNotNull(c.getMethod("picklistLabel",
            c.getClassLoader().loadClass("alloyx.runtime.PicklistEntry")));
        assertNotNull(c.getMethod("children",
            c.getClassLoader().loadClass("alloyx.runtime.DescribeSObjectResult")));
    }

    @Test
    void describeFieldResult_nameLocal_picklistValuesDegrade() throws Exception {
        // DescribeFieldResult answers getName() locally (the field API name), but getPicklistValues()
        // — org metadata the synced schema doesn't store — degrades clearly.
        alloyx.runtime.DescribeFieldResult d =
            new alloyx.runtime.DescribeFieldResult("Account", "Name");
        assertEquals("Name", d.getName());
        assertThrows(UnsupportedOperationException.class, d::getPicklistValues);
        assertThrows(UnsupportedOperationException.class, d::getLabel);
    }
}
