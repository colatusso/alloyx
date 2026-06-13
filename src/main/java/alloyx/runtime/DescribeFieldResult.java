// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema.DescribeFieldResult} — field-level describe metadata.
 *
 * <p>The synced schema stores each field's API name and Apex TYPE, so {@link #getName()} and
 * {@link #getType()} answer LOCALLY from the same describe the typer uses. Everything else —
 * labels, picklist value sets, accessibility/CRUD flags, reference targets — is ORG metadata the
 * synced schema does not carry, so those accessors degrade clearly ({@link Unsupported#notLocal})
 * rather than inventing data. One consistent philosophy with the rest of the Schema runtime.
 */
public final class DescribeFieldResult {
    private final String objectName;
    private final String fieldName;

    public DescribeFieldResult(String objectName, String fieldName) {
        this.objectName = objectName;
        this.fieldName = fieldName;
    }

    /** The field API name — known locally. */
    public String getName() {
        return fieldName;
    }

    public String getLocalName() {
        return fieldName;
    }

    /** The field's Apex/SOAP type, from the synced describe; degrades if the field isn't described. */
    public String getType() {
        String type = Database.gateway().describe(objectName).get(fieldName);
        if (type == null) {
            throw Unsupported.notLocal(
                "Schema.DescribeFieldResult.getType() for " + objectName + "." + fieldName);
        }
        return type;
    }

    /** A token identifying this field again (the same object+field). */
    public SObjectField getSObjectField() {
        return new SObjectField(objectName, fieldName);
    }

    // --- org metadata the synced schema does not carry: degrade clearly ----------------------------

    public String getLabel() {
        throw notLocal("getLabel()");
    }

    public List<PicklistEntry> getPicklistValues() {
        throw notLocal("getPicklistValues()");
    }

    public Integer getLength() {
        throw notLocal("getLength()");
    }

    public Integer getPrecision() {
        throw notLocal("getPrecision()");
    }

    public Integer getScale() {
        throw notLocal("getScale()");
    }

    public boolean isAccessible() {
        throw notLocal("isAccessible()");
    }

    public boolean isCreateable() {
        throw notLocal("isCreateable()");
    }

    public boolean isUpdateable() {
        throw notLocal("isUpdateable()");
    }

    public boolean isNillable() {
        throw notLocal("isNillable()");
    }

    public boolean isCustom() {
        throw notLocal("isCustom()");
    }

    public List<SObjectType> getReferenceTo() {
        throw notLocal("getReferenceTo()");
    }

    public String getRelationshipName() {
        throw notLocal("getRelationshipName()");
    }

    private UnsupportedOperationException notLocal(String member) {
        return Unsupported.notLocal(
            "Schema.DescribeFieldResult." + member + " for " + objectName + "." + fieldName);
    }
}
