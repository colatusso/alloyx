// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SchemaProvider} backed by an org describe, with two cache tiers:
 * an in-memory map and a per-sObject JSON file on disk (TTL'd). The transpiler
 * asks for {@code object.field}'s Apex type; we describe the sObject once, cache
 * the whole field map, and answer from cache until it goes stale.
 *
 * <p>Lookups are lenient: with no org connected, {@link #fieldType} returns
 * {@code null} (caller treats unknown fields as untyped/Object). The strict
 * variant {@link #describeStrict} instead propagates the missing-org error.
 *
 * <p>Disk format per file ({@code <cacheDir>/<obj>.json}):
 * <pre>{ "fetchedAt": &lt;epochMillis&gt;, "fields": { "Name": "String", ... } }</pre>
 */
public final class SchemaCache implements SchemaProvider {
    private static final String DEFAULT_DIR = ".apexcache/schema";
    private static final String GLOBAL_FILE = "_global.json";
    // The schema is refreshed only when the user asks (`allx schema sync`/`refresh`),
    // so the cache never expires on its own — describe once, run offline forever.
    private static final long DEFAULT_TTL_MILLIS = Long.MAX_VALUE;

    private final OrgGateway gateway;
    private final Path cacheDir;
    private final long ttlMillis;
    private final Gson gson = new Gson();

    /** In-memory tier: sObject API name -> (field/relationship API name -> Apex type). */
    private final java.util.Map<String, java.util.Map<String, String>> memory = new ConcurrentHashMap<>();

    /** In-memory tier: the org's full set of real sObject API names (global describe). */
    private volatile java.util.Set<String> globalCache;

    /** Convenience: cache under {@code .apexcache/schema} with a 24h TTL. */
    public SchemaCache(OrgGateway gateway) {
        this(gateway, Path.of(DEFAULT_DIR), DEFAULT_TTL_MILLIS);
    }

    public SchemaCache(OrgGateway gateway, Path cacheDir, long ttlMillis) {
        this.gateway = gateway;
        this.cacheDir = cacheDir;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Apex type of {@code sobjectType.fieldName}, or {@code null} if unknown.
     * Lenient: a missing org (no describe available) yields {@code null} rather
     * than throwing, so the transpiler simply leaves the field untyped.
     */
    @Override
    public String fieldType(String sobjectType, String fieldName) {
        java.util.Map<String, String> f = fields(sobjectType);
        if (f == null) {
            return null;
        }
        String exact = f.get(fieldName);
        return exact != null ? exact : f.get(canonicalField(sobjectType, fieldName));
    }

    /** Full field map for an sObject, or null when no schema is available (lenient). */
    @Override
    public java.util.Map<String, String> fields(String sobjectType) {
        try {
            return describeStrict(sobjectType);
        } catch (RuntimeException failure) {
            // no org, unknown sObject, or a transient describe error -> leave it untyped
            return null;
        }
    }

    /** True once the sObject has been described (cached) or an org can describe it. */
    @Override
    public boolean isDescribed(String sobjectType) {
        if (sobjectType.endsWith("__r")) {
            return false; // a relationship name, not an object
        }
        if (!isKnownSObject(sobjectType)) {
            return false; // org has a global list and this name isn't a real object in it
        }
        java.util.Map<String, String> f = fields(sobjectType);
        return f != null && !f.isEmpty();
    }

    /**
     * Every real sObject API name in the org (one global describe), cached; {@code null}
     * when unavailable (no org and no cached global). Lets us skip per-object describes on
     * the ~hundreds of Apex classes / relationship names / keywords a code scan turns up.
     */
    public java.util.Set<String> knownSObjects() {
        if (globalCache != null) {
            return globalCache;
        }
        java.util.Set<String> fromDisk = loadGlobal();
        if (fromDisk != null) {
            globalCache = fromDisk;
            return fromDisk;
        }
        try {
            java.util.Set<String> live = new java.util.LinkedHashSet<>(gateway.globalSObjects());
            globalCache = live;
            saveGlobal(live);
            return live;
        } catch (RuntimeException noOrg) {
            return null; // no org / no cached global -> can't filter, stay lenient
        }
    }

    /** Whether the org knows this sObject (case-insensitive); lenient (true) when no global is available. */
    public boolean isKnownSObject(String name) {
        java.util.Set<String> known = knownSObjects();
        if (known == null || known.contains(name)) {
            return true;
        }
        for (String k : known) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The org's canonical API name for a (possibly mis-cased) sObject reference, or the input
     * when no global describe is available (lenient path). A code scan turns up identifiers in
     * whatever case the source used — e.g. a variable named {@code account} — and generated
     * classes MUST use one canonical spelling: on a case-insensitive filesystem (macOS APFS),
     * account.java and Account.java are the same file, so mixed casings clobber each other.
     */
    public String canonicalSObject(String name) {
        java.util.Set<String> known = knownSObjects();
        if (known == null || known.contains(name)) {
            return name;
        }
        for (String k : known) {
            if (k.equalsIgnoreCase(name)) {
                return k;
            }
        }
        return name;
    }

    /** Resolve a (possibly mis-cased) field name to its canonical describe key. */
    @Override
    public String canonicalField(String sobjectType, String fieldName) {
        java.util.Map<String, String> f = fields(sobjectType);
        if (f == null || f.containsKey(fieldName)) {
            return fieldName;
        }
        for (String key : f.keySet()) {
            if (key.equalsIgnoreCase(fieldName)) {
                return key;
            }
        }
        return fieldName;
    }

    /**
     * The full field map for {@code sobjectType} (from cache if fresh, else a live
     * describe that is then cached). Propagates {@link OrgConnectionException} when
     * no org is connected — use {@link #fieldType} for the lenient path.
     */
    public java.util.Map<String, String> describeStrict(String sobjectType) {
        java.util.Map<String, String> cached = memory.get(sobjectType);
        if (cached != null) {
            return cached;
        }
        java.util.Map<String, String> fromDisk = loadFresh(sobjectType);
        if (fromDisk != null) {
            memory.put(sobjectType, fromDisk);
            return fromDisk;
        }
        // Wrap in a plain java.util.Map: the gateway may hand back an alloyx Map.
        java.util.Map<String, String> described = new LinkedHashMap<>(gateway.describe(sobjectType));
        memory.put(sobjectType, described);
        save(sobjectType, described);
        return described;
    }

    /** Drop the in-memory + on-disk cache for one sObject; next lookup re-describes. */
    public void refresh(String sobjectType) {
        memory.remove(sobjectType);
        try {
            Files.deleteIfExists(fileFor(sobjectType));
        } catch (IOException ignored) {
            // Best effort: a stale file will just be overwritten on the next describe.
        }
    }

    /** Drop the entire cache (memory + every {@code *.json} on disk). */
    public void refreshAll() {
        memory.clear();
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var stream = Files.list(cacheDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    // --- disk tier

    /** Read the on-disk map for an sObject if present AND within TTL; else null. */
    private java.util.Map<String, String> loadFresh(String sobjectType) {
        Path file = fileFor(sobjectType);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            long fetchedAt = root.has("fetchedAt") ? root.get("fetchedAt").getAsLong() : 0L;
            if (java.lang.System.currentTimeMillis() - fetchedAt > ttlMillis) {
                return null; // stale -> caller re-describes
            }
            java.util.Map<String, String> fields = new LinkedHashMap<>();
            JsonObject obj = root.getAsJsonObject("fields");
            if (obj != null) {
                for (var e : obj.entrySet()) {
                    fields.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
                }
            }
            return fields;
        } catch (Exception malformed) {
            return null; // unreadable/corrupt -> treat as a miss
        }
    }

    /** Persist the field map plus a fetch timestamp for TTL checks. */
    private void save(String sobjectType, java.util.Map<String, String> fields) {
        try {
            Files.createDirectories(cacheDir);
            JsonObject root = new JsonObject();
            root.addProperty("fetchedAt", java.lang.System.currentTimeMillis());
            root.add("fields", gson.toJsonTree(fields));
            Files.writeString(fileFor(sobjectType), gson.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Disk cache is an optimization; the in-memory copy is already set.
        }
    }

    private Path fileFor(String sobjectType) {
        return cacheDir.resolve(sobjectType + ".json");
    }

    // --- global describe tier (the org's list of real sObjects)

    private java.util.Set<String> loadGlobal() {
        Path file = cacheDir.resolve(GLOBAL_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            long fetchedAt = root.has("fetchedAt") ? root.get("fetchedAt").getAsLong() : 0L;
            if (java.lang.System.currentTimeMillis() - fetchedAt > ttlMillis) {
                return null;
            }
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            var arr = root.getAsJsonArray("sobjects");
            if (arr != null) {
                for (var el : arr) {
                    if (!el.isJsonNull()) {
                        out.add(el.getAsString());
                    }
                }
            }
            return out;
        } catch (Exception malformed) {
            return null;
        }
    }

    private void saveGlobal(java.util.Set<String> names) {
        try {
            Files.createDirectories(cacheDir);
            JsonObject root = new JsonObject();
            root.addProperty("fetchedAt", java.lang.System.currentTimeMillis());
            root.add("sobjects", gson.toJsonTree(names));
            Files.writeString(cacheDir.resolve(GLOBAL_FILE), gson.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // global cache is an optimization; the in-memory copy is already set
        }
    }
}
