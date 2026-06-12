// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gateway talks HTTP, so these tests exercise the parse/compose seams without a
 * live org: a fake {@link SalesforceGateway.SfRunner} satisfies auth, and a fake
 * {@link SalesforceGateway.HttpCaller} feeds canned response bodies / captures the
 * composed requests. Covers SOQL pagination, JSON value typing, and the upsert split.
 */
class SalesforceGatewayTest {

    /** Minimal auth payload so ensureAuth() succeeds offline. */
    private static final String AUTH =
        "{\"result\":{\"accessToken\":\"tok\",\"instanceUrl\":\"https://x.my.salesforce.com\"}}";

    private static SalesforceGateway.SfRunner okAuth() {
        return args -> AUTH;
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    @Test
    void query_followsNextRecordsUrlAcrossPages_preservingOrder() {
        String page1 = "{\"done\":false,\"nextRecordsUrl\":\"/services/data/v60.0/query/01g000\","
            + "\"records\":[{\"attributes\":{\"type\":\"Account\"},\"Name\":\"A\"},"
            + "{\"attributes\":{\"type\":\"Account\"},\"Name\":\"B\"}]}";
        String page2 = "{\"done\":true,"
            + "\"records\":[{\"attributes\":{\"type\":\"Account\"},\"Name\":\"C\"}]}";

        java.util.List<String> urls = new ArrayList<>();
        SalesforceGateway.HttpCaller http = (method, url, headers, body) -> {
            urls.add(url);
            return url.contains("/query/01g000") ? page2 : page1;
        };

        SalesforceGateway g = new SalesforceGateway("o", okAuth(), http);
        List<SObject> rows = g.query("SELECT Name FROM Account", Map.of());

        assertEquals(3, rows.size(), "both pages returned");
        assertEquals("A", rows.get(0).get("Name"));
        assertEquals("B", rows.get(1).get("Name"));
        assertEquals("C", rows.get(2).get("Name"), "order preserved across pages");
        // second GET composed from instanceUrl + the server-relative nextRecordsUrl, untouched
        assertEquals("https://x.my.salesforce.com/services/data/v60.0/query/01g000", urls.get(1));
    }

    // ------------------------------------------------------------------
    // JSON value typing
    // ------------------------------------------------------------------

    @Test
    void parseRecords_typesPrimitivesByJsonShape() {
        String json = "{\"done\":true,\"records\":[{\"attributes\":{\"type\":\"X\"},"
            + "\"a\":1,\"b\":2.5,\"c\":\"x\",\"d\":true,\"e\":null,\"f\":9999999999}]}";
        SObject r = SalesforceGateway.parseRecords(json).get(0);

        assertInstanceOf(Integer.class, r.get("a"));
        assertEquals(1, r.get("a"));
        assertInstanceOf(Decimal.class, r.get("b"));
        assertEquals(0, ((Decimal) r.get("b")).compareTo(new Decimal("2.5")));
        assertInstanceOf(String.class, r.get("c"));
        assertEquals("x", r.get("c"));
        assertInstanceOf(Boolean.class, r.get("d"));
        assertEquals(Boolean.TRUE, r.get("d"));
        assertNull(r.get("e"));
        assertInstanceOf(Long.class, r.get("f"), "beyond int range -> Long");
        assertEquals(9999999999L, r.get("f"));
    }

    @Test
    void parseRecords_idAndDateStayStrings_currencyWholeIsInteger() {
        // Salesforce returns Id/date/datetime as JSON strings -> no parsing.
        // A whole currency value ("100") is integral JSON -> Integer; the typed getter
        // (SObject.asDecimal) coerces it to Decimal at read time.
        String json = "{\"done\":true,\"records\":[{\"attributes\":{\"type\":\"Account\"},"
            + "\"Id\":\"001000000000001\",\"CreatedDate\":\"2026-01-01T00:00:00.000+0000\","
            + "\"Amount\":100}]}";
        SObject r = SalesforceGateway.parseRecords(json).get(0);

        assertInstanceOf(String.class, r.get("Id"));
        assertInstanceOf(String.class, r.get("CreatedDate"));
        assertInstanceOf(Integer.class, r.get("Amount"));
        // the consumer coercion (what SObjectClassGen / Transpiler emit) makes it a Decimal
        assertEquals(0, SObject.asDecimal(r.get("Amount")).compareTo(new Decimal("100")));
    }

    @Test
    void parseRecords_childSubquery_becomesListOfSObject() {
        // A SOQL child subquery comes back as a nested object carrying its own "records" array.
        // The gateway parses it into an alloyx List<SObject> under the relationship name, so a
        // child-relationship access (parent.OrderItems__r -> getSObjects("OrderItems__r")) reads it.
        String json = "{\"done\":true,\"records\":[{\"attributes\":{\"type\":\"Order__c\"},"
            + "\"Name\":\"O-1\",\"OrderItems__r\":{\"totalSize\":2,\"done\":true,\"records\":["
            + "{\"attributes\":{\"type\":\"OrderItem__c\"},\"Name\":\"L1\",\"Quantity__c\":3},"
            + "{\"attributes\":{\"type\":\"OrderItem__c\"},\"Name\":\"L2\",\"Quantity__c\":7}]}}]}";
        SObject order = SalesforceGateway.parseRecords(json).get(0);

        List<SObject> items = order.getSObjects("OrderItems__r");
        assertEquals(2, items.size());
        assertEquals("L1", items.get(0).get("Name"));
        assertEquals(3, items.get(0).get("Quantity__c"));
        assertEquals("OrderItem__c", items.get(1).getSObjectType().toString());
    }

    @Test
    void parseRecords_parentRecord_becomesNestedSObject() {
        // A parent relationship (Account) comes back as a nested record object (no "records" array);
        // it parses into a single nested SObject, read back via getSObject(name).
        String json = "{\"done\":true,\"records\":[{\"attributes\":{\"type\":\"Order__c\"},"
            + "\"Name\":\"O-1\",\"Account__r\":{\"attributes\":{\"type\":\"Account\"},"
            + "\"Name\":\"Acme\"}}]}";
        SObject order = SalesforceGateway.parseRecords(json).get(0);

        SObject account = order.getSObject("Account__r");
        assertEquals("Acme", account.get("Name"));
        assertEquals("Account", account.getSObjectType().toString());
    }

    // ------------------------------------------------------------------
    // upsert split (Id -> update/PATCH, no Id -> insert/POST)
    // ------------------------------------------------------------------

    @Test
    void upsert_routesByIdPresence() {
        java.util.List<String> calls = new ArrayList<>();
        SalesforceGateway.HttpCaller http = (method, url, headers, body) -> {
            calls.add(method + " " + url);
            return method.equals("POST") ? "{\"id\":\"001NEW\"}" : "";
        };

        SalesforceGateway g = new SalesforceGateway("o", okAuth(), http);
        List<SObject> recs = new List<>();
        recs.add(new SObject("Account", "Id", "001AAA", "Name", "HasId1"));
        recs.add(new SObject("Account", "Id", "001BBB", "Name", "HasId2"));
        recs.add(new SObject("Account", "Name", "NoId"));
        g.upsert(recs);

        long patches = calls.stream().filter(c -> c.startsWith("PATCH")).count();
        long posts = calls.stream().filter(c -> c.startsWith("POST")).count();
        assertEquals(2, patches, "the two records with an Id go down the update/PATCH path");
        assertEquals(1, posts, "the record without an Id goes down the insert/POST path");
        assertTrue(calls.stream().anyMatch(c -> c.contains("/sobjects/Account/001AAA")),
            "update PATCHes by Id");
        assertTrue(calls.stream().anyMatch(c -> c.equals("POST https://x.my.salesforce.com"
            + "/services/data/v60.0/sobjects/Account")), "insert POSTs to the collection URL");
    }
}
