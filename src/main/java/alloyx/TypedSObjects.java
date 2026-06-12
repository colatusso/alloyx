// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Case-INSENSITIVE view over the set of sObjects with a generated typed class. Apex is
 * case-insensitive ({@code account acc;} and {@code Account acc;} name the same type), but the set
 * holds only the org's CANONICAL casing — deduped so the generated .java don't clobber each other on
 * a case-insensitive filesystem (see {@code SchemaCache.canonicalSObject}). So a type written in any
 * other case must still resolve to that canonical entry, and the emitted Java must use the canonical
 * spelling to link against the generated {@code class Account}. This wrapper owns that one lowercase
 * -> canonical map (built once) so every membership/lookup is case-insensitive in ONE place.
 *
 * <p>It exposes {@link #has(String)} (case-insensitive membership) and {@link #canonical(String)}
 * (resolve a possibly-miscased name to its canonical entry, or {@code null} when absent). Built from
 * the {@code Set<String>} threaded through the existing signatures, so callers keep passing a Set.
 */
final class TypedSObjects {
    // lowercase API name -> the canonical-cased name in the set
    private final Map<String, String> byLower = new HashMap<>();

    TypedSObjects(Set<String> canonicalNames) {
        for (String n : canonicalNames) {
            byLower.put(n.toLowerCase(Locale.ROOT), n);
        }
    }

    /** Whether {@code name} denotes a generated typed sObject, matched case-insensitively (Apex). */
    boolean has(String name) {
        return name != null && byLower.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The canonical-cased typed-sObject name for a (possibly mis-cased) {@code name}, or {@code null}
     * when none is generated. Callers that EMIT a type name use this so the Java links against the
     * generated class (a lowercase {@code account} decl emits {@code Account}).
     */
    String canonical(String name) {
        return name == null ? null : byLower.get(name.toLowerCase(Locale.ROOT));
    }
}
