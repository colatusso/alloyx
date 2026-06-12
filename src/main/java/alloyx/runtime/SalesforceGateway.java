// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real org middleware. Auth is delegated to the {@code sf} CLI
 * ({@code sf org display --json} -> access token + instance URL), then used as a
 * Bearer token against the Query/REST API. The CLI call ({@link SfRunner}) and
 * the HTTP call ({@link HttpCaller}) are the only external boundaries — both are
 * injectable, so the gateway is fully testable without a live org.
 *
 * Note (first cut): SOQL binds are inlined (the REST Query API has no native
 * binds); the token is cached (call {@link #refresh()} on expiry). Bulk API later.
 */
public final class SalesforceGateway implements OrgGateway {
    @FunctionalInterface
    public interface SfRunner {
        String run(java.util.List<String> args) throws Exception; // returns stdout JSON
    }

    @FunctionalInterface
    public interface HttpCaller {
        String call(String method, String url, java.util.Map<String, String> headers, String body) throws Exception;
    }

    private static final Pattern BIND = Pattern.compile(":(\\w+)");
    private final Gson gson = new Gson();

    private final String org;
    private final SfRunner sf;
    private final HttpCaller http;
    private String apiVersion = "60.0";
    private String token;
    private String instanceUrl;

    public SalesforceGateway(String org) {
        this(org, SalesforceGateway::runSf, SalesforceGateway::httpCall);
    }

    public SalesforceGateway(String org, SfRunner sf, HttpCaller http) {
        this.org = org;
        this.sf = sf;
        this.http = http;
    }

    public void refresh() {
        token = null;
    }

    private void ensureAuth() throws Exception {
        if (token != null) {
            return;
        }
        java.util.List<String> args = new ArrayList<>(java.util.List.of("sf", "org", "display", "--json"));
        if (org != null) {
            args.add("--target-org");
            args.add(org);
        }
        JsonObject result = JsonParser.parseString(sf.run(args)).getAsJsonObject().getAsJsonObject("result");
        token = result.get("accessToken").getAsString();
        instanceUrl = result.get("instanceUrl").getAsString();
        if (result.has("apiVersion") && !result.get("apiVersion").isJsonNull()) {
            apiVersion = result.get("apiVersion").getAsString();
        }
    }

    private java.util.Map<String, String> authHeaders() {
        java.util.Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        return h;
    }

    private String dataUrl(String path) {
        return instanceUrl + "/services/data/v" + apiVersion + path;
    }

    @Override
    public List<SObject> query(String soql, java.util.Map<String, Object> binds) {
        try {
            ensureAuth();
            String q = inlineBinds(soql, binds);
            String url = dataUrl("/query") + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            List<SObject> out = new List<>();
            // Salesforce caps a /query page (default 2000 rows). When the result spills
            // over, the body carries "done": false + a server-relative "nextRecordsUrl"
            // (".../query/01g..."); follow it as-is (already encoded, do not re-append
            // /query) until done, accumulating every page into the same list.
            String json = httpGet(url);
            while (true) {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                appendRecords(root, out);
                if (root.has("done") && !root.get("done").getAsBoolean()
                        && root.has("nextRecordsUrl") && !root.get("nextRecordsUrl").isJsonNull()) {
                    json = httpGet(instanceUrl + root.get("nextRecordsUrl").getAsString());
                } else {
                    return out;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** GET seam: goes through the injectable {@link HttpCaller}, so tests feed page JSON. */
    private String httpGet(String url) throws Exception {
        return http.call("GET", url, authHeaders(), null);
    }

    @Override
    public void insert(List<SObject> records) {
        try {
            ensureAuth();
            for (SObject r : records) {
                String resp = http.call("POST", dataUrl("/sobjects/" + r.getSObjectType()),
                    authHeaders(), gson.toJson(r.getFields()));
                JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
                if (o.has("id")) {
                    r.put("Id", o.get("id").getAsString());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(List<SObject> records) {
        try {
            ensureAuth();
            for (SObject r : records) {
                java.util.Map<String, Object> body = new LinkedHashMap<>(r.getFields());
                Object id = body.remove("Id");
                http.call("PATCH", dataUrl("/sobjects/" + r.getSObjectType() + "/" + id),
                    authHeaders(), gson.toJson(body));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void upsert(List<SObject> records) {
        // REST has no batch upsert by Id, so split per the same rule the REST API uses:
        // a record carrying an Id is an UPDATE (PATCH /sobjects/<Type>/<Id>), one without
        // is an INSERT (POST /sobjects/<Type>). The gateway's upsert carries no external-id
        // field (Database.upsert drops it locally), so the by-external-id endpoint isn't used.
        List<SObject> toInsert = new List<>();
        List<SObject> toUpdate = new List<>();
        for (SObject r : records) {
            Object id = r.get("Id");
            if (id == null || id.toString().isEmpty()) {
                toInsert.add(r);
            } else {
                toUpdate.add(r);
            }
        }
        if (!toUpdate.isEmpty()) {
            update(toUpdate);
        }
        if (!toInsert.isEmpty()) {
            insert(toInsert);
        }
    }

    @Override
    public void delete(List<SObject> records) {
        try {
            ensureAuth();
            for (SObject r : records) {
                http.call("DELETE", dataUrl("/sobjects/" + r.getSObjectType() + "/" + r.get("Id")),
                    authHeaders(), null);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.Map<String, String> describe(String sobjectType) {
        try {
            ensureAuth();
            String resp = http.call("GET", dataUrl("/sobjects/" + sobjectType + "/describe"),
                authHeaders(), null);
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            JsonArray fields = root.getAsJsonArray("fields");
            java.util.Map<String, String> out = new LinkedHashMap<>();
            if (fields != null) {
                for (JsonElement el : fields) {
                    JsonObject f = el.getAsJsonObject();
                    String name = str(f, "name");
                    String sfType = str(f, "type");
                    if (name == null || sfType == null) {
                        continue;
                    }
                    out.put(name, apexType(sfType));
                    // A reference field also exposes its parent under the relationship
                    // name, typed as the related sObject (e.g. "Owner" -> "User").
                    if (sfType.equals("reference")) {
                        String rel = str(f, "relationshipName");
                        String related = firstReferenceTo(f);
                        if (rel != null && related != null) {
                            out.put(rel, related);
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.Set<String> globalSObjects() {
        try {
            ensureAuth();
            String resp = http.call("GET", dataUrl("/sobjects"), authHeaders(), null);
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            JsonArray sobjects = root.getAsJsonArray("sobjects");
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            if (sobjects != null) {
                for (JsonElement el : sobjects) {
                    String name = str(el.getAsJsonObject(), "name");
                    if (name != null) {
                        out.add(name);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Salesforce describe field "type" -> Apex type name. Unknown -> "String". */
    private static String apexType(String sfType) {
        switch (sfType) {
            case "id":
                return "Id";
            case "boolean":
                return "Boolean";
            case "int":
                return "Integer";
            case "double":
            case "currency":
            case "percent":
                return "Decimal";
            case "date":
                return "Date";
            case "datetime":
                return "Datetime";
            case "time":
                return "Time";
            case "reference":
                return "Id";
            case "string":
            case "textarea":
            case "phone":
            case "email":
            case "url":
            case "picklist":
            case "multipicklist":
            case "encryptedstring":
            case "combobox":
            case "base64":
                return "String";
            default:
                return "String";
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String firstReferenceTo(JsonObject f) {
        if (!f.has("referenceTo") || f.get("referenceTo").isJsonNull()) {
            return null;
        }
        JsonArray refs = f.getAsJsonArray("referenceTo");
        if (refs == null || refs.size() == 0 || refs.get(0).isJsonNull()) {
            return null;
        }
        return refs.get(0).getAsString();
    }

    // --- helpers
    /** Parse one whole /query response (single page). Kept for direct testing. */
    static List<SObject> parseRecords(String json) {
        List<SObject> out = new List<>();
        appendRecords(JsonParser.parseString(json).getAsJsonObject(), out);
        return out;
    }

    /** Append this page's "records" array (typed via {@link #jsonValue}) into {@code out}. */
    static void appendRecords(JsonObject root, List<SObject> out) {
        JsonArray records = root.getAsJsonArray("records");
        if (records == null) {
            return;
        }
        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            String type = rec.has("attributes")
                ? rec.getAsJsonObject("attributes").get("type").getAsString() : "SObject";
            java.util.List<Object> kv = new ArrayList<>();
            for (var entry : rec.entrySet()) {
                if (entry.getKey().equals("attributes")) {
                    continue;
                }
                kv.add(entry.getKey());
                kv.add(jsonValue(entry.getValue()));
            }
            out.add(new SObject(type, kv.toArray()));
        }
    }

    /**
     * Map a SOQL JSON value to the runtime type so downstream arithmetic matches the
     * org: booleans -> Boolean, integral numbers -> Integer (or Long on int overflow),
     * fractional numbers -> {@link Decimal}, everything else (Id/date/datetime/text) ->
     * String. The integral/fractional split mirrors {@link JSON#deserializeUntyped} and
     * uses BigDecimal.scale to avoid a double round-trip. Nested objects/arrays (parent
     * records, subqueries) keep their existing raw-JSON-string behavior — out of scope.
     */
    private static Object jsonValue(JsonElement el) {
        if (el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                java.math.BigDecimal n = p.getAsBigDecimal();
                if (n.stripTrailingZeros().scale() <= 0) {
                    java.math.BigInteger i = n.toBigInteger();
                    if (i.bitLength() < 32) {
                        return i.intValue(); // fits a 32-bit Apex Integer
                    }
                    if (i.bitLength() < 64) {
                        return i.longValue();
                    }
                    return new Decimal(n.toPlainString()); // beyond Long: keep precision
                }
                return new Decimal(n.toPlainString());
            }
            return p.getAsString(); // Id/date/datetime/text stay String
        }
        return el.toString();
    }

    static String inlineBinds(String soql, java.util.Map<String, Object> binds) {
        Matcher m = BIND.matcher(soql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = binds.containsKey(key) ? formatValue(binds.get(key)) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String formatValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        // qualified: the unqualified name resolves to the same-package Apex-shaped alloyx Iterable
        if (v instanceof java.lang.Iterable<?> it) {
            java.util.List<String> parts = new ArrayList<>();
            for (Object o : it) {
                parts.add(formatValue(o));
            }
            return "(" + String.join(", ", parts) + ")";
        }
        return "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // --- real external boundaries (replaced by fakes in tests)
    static String runSf(java.util.List<String> args) throws Exception {
        Process p = new ProcessBuilder(args).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    static String httpCall(String method, String url, java.util.Map<String, String> headers, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        headers.forEach(b::header);
        b.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> resp = HttpClient.newHttpClient()
            .send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }
}
