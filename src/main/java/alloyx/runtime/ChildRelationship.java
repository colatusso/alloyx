// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema.ChildRelationship} — a child-relationship entry of an sObject's describe.
 *
 * <p>Child-relationship metadata (the child sObject, the relationship/field names) lives in the
 * parent's full describe, which the synced schema does NOT store (it keeps only field name->type
 * maps). With no local source, the accessors degrade clearly ({@link Unsupported#notLocal}) rather
 * than inventing a relationship. The type surface is recognized so a {@code Schema.ChildRelationship}
 * variable and its {@code getRelationshipName()}/{@code getChildSObject()} reads type-check.
 */
public final class ChildRelationship {
    private ChildRelationship() {
    }

    public String getRelationshipName() {
        throw Unsupported.notLocal("Schema.ChildRelationship.getRelationshipName()");
    }

    public SObjectType getChildSObject() {
        throw Unsupported.notLocal("Schema.ChildRelationship.getChildSObject()");
    }

    public SObjectField getField() {
        throw Unsupported.notLocal("Schema.ChildRelationship.getField()");
    }

    public boolean isCascadeDelete() {
        throw Unsupported.notLocal("Schema.ChildRelationship.isCascadeDelete()");
    }

    public boolean isDeprecatedAndHidden() {
        throw Unsupported.notLocal("Schema.ChildRelationship.isDeprecatedAndHidden()");
    }
}
