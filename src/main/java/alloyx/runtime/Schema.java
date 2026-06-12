// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema} namespace entry points.
 *
 * <p>{@link SObjectType} / {@link DescribeSObjectResult} (the describe TOKENS) live in their
 * own runtime classes and are reached via {@code SObject.getSObjectType().getDescribe()},
 * which reflects the connected org. What lives here are the namespace-level statics.
 *
 * <p>DESIGN: {@code Schema.getGlobalDescribe()} enumerates EVERY object in the org — there is
 * no local catalog of all objects, so it fails clearly rather than returning a half-truth.
 * The static describe-token access pattern {@code Schema.SObjectType.<Name>.fields...} is
 * org-coupled too and is degraded to {@link Object} by the transpiler (routed through
 * {@link #describeToken(String)}); the per-object describe that AlloyX DOES back lives on
 * {@code DescribeSObjectResult} above.
 */
public final class Schema {
    private Schema() {}

    /** No local catalog of all org objects exists, so this fails clearly. Returns the
     *  Apex Map type so {@code Map<String, Schema.SObjectType> m = Schema.getGlobalDescribe()}
     *  type-checks against the transpiled (runtime Map) declaration. */
    public static Map<String, SObjectType> getGlobalDescribe() {
        throw Unsupported.notLocal("Schema.getGlobalDescribe()");
    }

    public static DescribeSObjectResult describe(String sobjectApiName) {
        return new SObjectType(sobjectApiName).getDescribe();
    }

    /** Placeholder for the static {@code Schema.SObjectType.<Name>...} describe chain. */
    public static Object describeToken(String what) {
        throw Unsupported.notLocal("Schema.SObjectType." + what);
    }
}
