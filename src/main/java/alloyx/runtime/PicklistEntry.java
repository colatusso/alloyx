// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema.PicklistEntry} — one value of a picklist field's describe.
 *
 * <p>Picklist metadata (the values, their labels, which are active/default) is ORG metadata: the
 * synced schema stores field names and types, never picklist value sets. With no local source to
 * answer from, inspecting an entry degrades clearly ({@link Unsupported#notLocal}) rather than
 * inventing values. The type surface is recognized so {@code Schema.PicklistEntry pe = ...} and the
 * usual {@code pe.getLabel()}/{@code pe.getValue()} reads type-check.
 */
public final class PicklistEntry {
    private PicklistEntry() {
    }

    public String getLabel() {
        throw Unsupported.notLocal("Schema.PicklistEntry.getLabel()");
    }

    public String getValue() {
        throw Unsupported.notLocal("Schema.PicklistEntry.getValue()");
    }

    public boolean isActive() {
        throw Unsupported.notLocal("Schema.PicklistEntry.isActive()");
    }

    public boolean isDefaultValue() {
        throw Unsupported.notLocal("Schema.PicklistEntry.isDefaultValue()");
    }

    public String getValidFor() {
        throw Unsupported.notLocal("Schema.PicklistEntry.getValidFor()");
    }
}
