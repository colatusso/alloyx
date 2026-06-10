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

    // --- numeric field coercion -------------------------------------------
    // SOQL returns numbers as JSON primitives that the gateway maps to Integer,
    // Long or Decimal based on the literal (integral vs fractional), independent
    // of the field's describe type: a currency field is "100" on one row and
    // "100.5" on the next. The typed getters (SObjectClassGen) and the described
    // dynamic-access path (Transpiler) declare one Java type per field, so they
    // route reads through these helpers to coerce any numeric runtime type to the
    // field's declared type. Non-numeric values and null pass through untouched.

    /** Coerce a stored field value to {@link Decimal} (currency/percent/double fields). */
    public static Decimal asDecimal(Object v) {
        return v == null ? null : Decimal.valueOf(v);
    }

    /** Coerce a stored field value to {@code Integer} (Apex Integer fields). */
    public static Integer asInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(v.toString().trim());
    }

    /** Coerce a stored field value to {@code Long} (Apex Long fields). */
    public static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(v.toString().trim());
    }

    /** Coerce a stored field value to {@code Double} (Apex Double fields). */
    public static Double asDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(v.toString().trim());
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
