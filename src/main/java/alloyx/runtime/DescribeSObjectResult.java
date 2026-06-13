// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.LinkedHashMap;

/**
 * Apex {@code Schema.DescribeSObjectResult} — describe metadata for one sObject.
 * The API name is carried directly; field metadata is pulled from the SAME real
 * org describe the typer already uses ({@link OrgGateway#describe}). Nothing here
 * is hardcoded — it reflects the connected org.
 *
 * <p>WHAT IS LOCAL: the object's API name, and the field TOKEN map ({@link #fields()} ->
 * {@code getMap()}), built from the synced field describe. WHAT DEGRADES: record types,
 * child relationships, key prefix, and CRUD flags are org metadata the synced schema does
 * not store, so they fail clearly ({@link Unsupported#notLocal}) rather than inventing data —
 * one consistent philosophy with {@link DescribeFieldResult} / {@link PicklistEntry}.
 */
public final class DescribeSObjectResult {
    private final String name;
    /**
     * Apex {@code describeResult.fields} — accessed as a FIELD in Apex source ({@code .fields.getMap()}),
     * so it's a public field here (the transpiler emits a bare member read for it). Its {@code getMap()}
     * yields the field TOKEN map from the synced describe.
     */
    public final Fields fields;

    public DescribeSObjectResult(String name) {
        this.name = name;
        this.fields = new Fields(name);
    }

    public String getName() {
        return name;
    }

    public String getLocalName() {
        return name;
    }

    public String getLabel() {
        throw Unsupported.notLocal("Schema.DescribeSObjectResult.getLabel() for " + name);
    }

    public String getLabelPlural() {
        throw Unsupported.notLocal("Schema.DescribeSObjectResult.getLabelPlural() for " + name);
    }

    /** Field/relationship API name -> Apex type, from the live/cached org describe. */
    public java.util.Map<String, String> getFieldsMap() {
        return Database.gateway().describe(name);
    }

    /** The describe TOKEN for this sObject type. */
    public SObjectType getSObjectType() {
        return new SObjectType(name);
    }

    // --- org metadata the synced schema does not carry: degrade clearly ----------------------------

    public String getKeyPrefix() {
        throw notLocal("getKeyPrefix()");
    }

    public List<RecordTypeInfo> getRecordTypeInfos() {
        throw notLocal("getRecordTypeInfos()");
    }

    public java.util.Map<String, RecordTypeInfo> getRecordTypeInfosByName() {
        throw notLocal("getRecordTypeInfosByName()");
    }

    public java.util.Map<String, RecordTypeInfo> getRecordTypeInfosByDeveloperName() {
        throw notLocal("getRecordTypeInfosByDeveloperName()");
    }

    public java.util.Map<String, RecordTypeInfo> getRecordTypeInfosById() {
        throw notLocal("getRecordTypeInfosById()");
    }

    public List<ChildRelationship> getChildRelationships() {
        throw notLocal("getChildRelationships()");
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

    public boolean isDeletable() {
        throw notLocal("isDeletable()");
    }

    public boolean isQueryable() {
        throw notLocal("isQueryable()");
    }

    public boolean isCustom() {
        throw notLocal("isCustom()");
    }

    private UnsupportedOperationException notLocal(String member) {
        return Unsupported.notLocal("Schema.DescribeSObjectResult." + member + " for " + name);
    }

    /**
     * Apex {@code describeResult.fields} accessor object. Its {@code getMap()} returns the field
     * TOKEN map (API name -> {@link SObjectField}), assembled from the synced describe.
     */
    public static final class Fields {
        private final String objectName;

        Fields(String objectName) {
            this.objectName = objectName;
        }

        /** API field name -> field TOKEN, from the synced describe. */
        public java.util.Map<String, SObjectField> getMap() {
            java.util.Map<String, SObjectField> out = new LinkedHashMap<>();
            for (String field : Database.gateway().describe(objectName).keySet()) {
                out.put(field, new SObjectField(objectName, field));
            }
            return out;
        }

        /** A single field token by API name (Apex {@code fields.getName()}-style lookup). */
        public SObjectField getSObjectField(String fieldName) {
            return new SObjectField(objectName, fieldName);
        }
    }
}
