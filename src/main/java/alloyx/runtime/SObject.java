// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * A dynamic Salesforce record: a type + a bag of fields. The transpiler builds
 * these from {@code new Account(Name='x')} (emitted as
 * {@code new SObject("Account", "Name", "x")}); field access goes through
 * get/put. Unset fields read as null.
 */
public class SObject {
    private final String sobjectType;
    private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

    public SObject(String type, Object... keyValues) {
        this.sobjectType = type;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put((String) keyValues[i], keyValues[i + 1]);
        }
    }

    public Object get(String name) {
        return fields.get(name);
    }

    public void put(String name, Object value) {
        fields.put(name, value);
    }

    /** Apex returns Schema.SObjectType here (not a String); it stringifies to the API name. */
    public SObjectType getSObjectType() {
        return new SObjectType(sobjectType);
    }

    /** Child records of a subquery relationship (e.g. {@code acc.getSObjects("Contacts")}), or null. */
    @SuppressWarnings("unchecked")
    public List<SObject> getSObjects(String relationshipName) {
        Object v = fields.get(relationshipName);
        return v instanceof List ? (List<SObject>) v : null;
    }

    /** Parent record of a relationship (e.g. {@code con.getSObject("Account")}), or null. */
    public SObject getSObject(String relationshipName) {
        Object v = fields.get(relationshipName);
        return v instanceof SObject ? (SObject) v : null;
    }

    /** Field API name -> value, only for the fields that are set (Apex getPopulatedFieldsAsMap). */
    public java.util.Map<String, Object> getPopulatedFieldsAsMap() {
        return fields;
    }

    /** A shallow copy of this record. The Apex clone(...) flags don't apply locally — ignored. */
    public SObject clone() {
        SObject copy = new SObject(sobjectType);
        copy.fields.putAll(this.fields);
        return copy;
    }

    public SObject clone(Object... flags) {
        return clone();
    }

    /** Apex trigger/DML validation hook; no-op locally. */
    public void addError(String message) {
    }

    public void addError(String message, Boolean escape) {
    }

    public java.util.Map<String, Object> getFields() {
        return fields;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SObject s
            && sobjectType.equals(s.sobjectType)
            && fields.equals(s.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sobjectType, fields);
    }

    @Override
    public String toString() {
        return sobjectType + fields;
    }
}
