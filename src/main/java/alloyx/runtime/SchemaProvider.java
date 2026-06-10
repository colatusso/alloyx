// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Resolves sObject schema (field types, relationships) so the transpiler can give
 * sObject field access a real type instead of Object. Backed by {@link SchemaCache},
 * which describes against the connected org and caches with a TTL.
 */
public interface SchemaProvider {
    /**
     * The Apex type of {@code object.field} — e.g. "String", "Integer", "Decimal",
     * "Date", "Datetime", "Boolean", "Id"; for a relationship field, the related
     * sObject's API name. Returns {@code null} when unknown (no schema / no org),
     * which the transpiler treats as "leave it untyped (Object)".
     */
    String fieldType(String sobjectType, String fieldName);

    /**
     * Whether this sObject's schema is available (cached, or describable from a
     * connected org), so the transpiler can generate a typed class for it.
     * Default {@code false} (no schema -> everything stays the generic SObject).
     */
    default boolean isDescribed(String sobjectType) {
        return false;
    }

    /**
     * The canonical (correctly-cased) field/relationship API name — Apex is
     * case-insensitive, Java is not, so {@code a.name} must resolve to the
     * generated {@code getName()}. Returns the input unchanged when unknown.
     */
    default String canonicalField(String sobjectType, String fieldName) {
        return fieldName;
    }

    /**
     * The full field/relationship -> Apex type map for an sObject, or {@code null}
     * when unavailable (lenient). Used to generate the typed class.
     */
    default java.util.Map<String, String> fields(String sobjectType) {
        return null;
    }
}
