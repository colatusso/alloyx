// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code AggregateResult} — the row type of an aggregate SOQL (COUNT/SUM/GROUP BY).
 * In Apex it IS an SObject subtype, so extending the runtime {@link SObject} lets a
 * {@code List<AggregateResult>} flow through the same query plumbing that yields SObjects,
 * and {@code get(alias)} reads an aliased aggregate the same way a field read does.
 */
public final class AggregateResult extends SObject {
    public AggregateResult(Object... keyValues) {
        super("AggregateResult", keyValues);
    }
}
