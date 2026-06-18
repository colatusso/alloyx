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

    /** Append this page's "records" array (each parsed via {@link #parseRecord}) into {@code out}. */
    static void appendRecords(JsonObject root, List<SObject> out) {
        JsonArray records = root.getAsJsonArray("records");
        if (records == null) {
            return;
        }
        for (JsonElement el : records) {
            out.add(parseRecord(el.getAsJsonObject()));
        }
    }

    /** One JSON record -> an {@link SObject}: its type (from "attributes") plus every field value. */
    private static SObject parseRecord(JsonObject rec) {
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
        return new SObject(type, kv.toArray());
    }

    /**
     * Map a SOQL JSON value to the runtime type so downstream arithmetic matches the
     * org: booleans -> Boolean, integral numbers -> Integer (or Long on int overflow),
     * fractional numbers -> {@link Decimal}, everything else (Id/date/datetime/text) ->
     * String. The integral/fractional split mirrors {@link JSON#deserializeUntyped} and
     * uses BigDecimal.scale to avoid a double round-trip.
     *
     * <p>Nested objects become records, matching the dynamic relationship accessors: a child
     * subquery (a JSON object carrying a "records" array) -> a {@link List} of {@link SObject}
     * (read back via {@code rec.getSObjects(name)} / a child-relationship {@code __r} access); a
     * parent record (a nested object without "records") -> a single {@link SObject}
     * ({@code rec.getSObject(name)}). This is what populates {@code parent.Children__r} at runtime.
     */
    private static Object jsonValue(JsonElement el) {
        if (el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("records")) {
                List<SObject> children = new List<>();
                appendRecords(obj, children); // child subquery -> alloyx List<SObject>
                return children;
            }
            return parseRecord(obj); // parent record -> nested SObject
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
        Process p = new ProcessBuilder(launchCommand(args)).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /**
     * Build the actual OS command for {@code args} (whose head is the bare program
     * name, e.g. {@code "sf"}). On Windows the Salesforce CLI is usually shipped as
     * {@code sf.cmd}/{@code sf.ps1} (npm), and Java's {@code CreateProcess} does no
     * PATHEXT resolution — so a bare {@code "sf"} silently fails to launch and auth
     * never happens. Resolve the real file on PATH: a {@code .exe}/{@code .com} runs
     * directly; a {@code .cmd}/{@code .bat} can only be launched via {@code cmd.exe}.
     * On macOS/Linux the command is returned unchanged (historical behaviour).
     */
    static java.util.List<String> launchCommand(java.util.List<String> args) throws Exception {
        boolean windows = java.lang.System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows || args.isEmpty()) {
            return args;
        }
        java.nio.file.Path exe = resolveOnPath(args.get(0));
        if (exe == null) {
            throw new java.io.IOException("`" + args.get(0)
                + "` was not found on PATH; install the Salesforce CLI and reopen the terminal");
        }
        String name = exe.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> tail = args.subList(1, args.size());
        if (name.endsWith(".cmd") || name.endsWith(".bat")) {
            // cmd.exe re-tokenizes its line (%VAR%, & | < > ^ " ( )); only the org alias
            // is user-influenced here, so reject anything outside a safe alias charset
            // rather than try to quote — real sf aliases never contain these.
            for (String a : tail) {
                if (!a.matches("[A-Za-z0-9._@:/\\\\-]+")) {
                    throw new java.io.IOException("unsafe character in sf argument: " + a);
                }
            }
            java.util.List<String> cmd = new ArrayList<>(java.util.List.of("cmd.exe", "/c", exe.toString()));
            cmd.addAll(tail);
            return cmd;
        }
        // .exe/.com (or anything else): launch the resolved absolute path directly
        java.util.List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(tail);
        return cmd;
    }

    /** First existing PATH entry for {@code name}, trying each PATHEXT extension (Windows). */
    private static java.nio.file.Path resolveOnPath(String name) {
        return resolveOnPath(name, java.lang.System.getenv("PATH"), java.lang.System.getenv("PATHEXT"),
            java.nio.file.Files::isRegularFile);
    }

    /**
     * Pure resolver (test seam): first {@code dir\name+ext} that {@code exists} accepts,
     * scanning PATH dirs in order and, within each, the PATHEXT extensions. The bare name
     * (no extension) is tried ONLY when {@code name} already carries a dot — never for a
     * plain {@code "sf"}: the Salesforce CLI ships an extensionless Unix wrapper next to
     * {@code sf.cmd}, and that bash script is not a valid Win32 executable (CreateProcess
     * error 193). PATHEXT default order (.COM;.EXE;.BAT;.CMD) prefers a real .exe over .cmd.
     */
    static java.nio.file.Path resolveOnPath(String name, String path, String pathext,
            java.util.function.Predicate<java.nio.file.Path> exists) {
        if (path == null || path.isBlank()) {
            return null;
        }
        java.util.List<String> exts = new ArrayList<>();
        if (name.indexOf('.') >= 0) {
            exts.add(""); // already extensioned (e.g. "sf.cmd") — accept as given
        }
        for (String e : (pathext == null || pathext.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathext).split(";")) {
            if (!e.isBlank()) {
                exts.add(e);
            }
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : exts) {
                java.nio.file.Path cand = java.nio.file.Path.of(dir, name + ext);
                if (exists.test(cand)) {
                    return cand;
                }
            }
        }
        return null;
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
