// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema.SObjectField} — a field-describe TOKEN identifying one field on an
 * sObject type. Selector-pattern code references it statically off the sObject TYPE
 * ({@code Item__c.Id}, {@code Item__c.Name__c}) and collects the tokens to build SOQL.
 *
 * <p>The token carries its object + field API names. Crucially, {@link #toString()}
 * returns the FIELD API name — real Apex's {@code SObjectField.toString()} does the same,
 * and selector code relies on it when joining field tokens into a SOQL string
 * ({@code String.join(fields, ',')}). The full describe ({@code getDescribe()}) is
 * org-coupled and degrades clearly when no local schema backs the field.
 */
public final class SObjectField {
    private final String objectName;
    private final String fieldName;

    public SObjectField(String objectName, String fieldName) {
        this.objectName = objectName;
        this.fieldName = fieldName;
    }

    /**
     * Apex {@code getDescribe()}: field-level describe. The name + Apex type answer from the synced
     * schema (see {@link DescribeFieldResult}); org-only metadata (label, picklist values, CRUD)
     * degrades clearly there.
     */
    public DescribeFieldResult getDescribe() {
        return new DescribeFieldResult(objectName, fieldName);
    }

    public String getName() {
        return fieldName;
    }

    /** The field API name — what selector code joins into a SOQL column list. */
    @Override
    public String toString() {
        return fieldName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SObjectField other
            && java.util.Objects.equals(objectName, other.objectName)
            && java.util.Objects.equals(fieldName, other.fieldName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(objectName, fieldName);
    }
}
