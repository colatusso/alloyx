// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import java.util.Map;
import java.util.Set;

/**
 * Generates a typed Java class for a Salesforce sObject from its describe map
 * (field API name -> Apex type). Each field becomes a typed getter/setter backed
 * by the inherited dynamic field map, so javac catches bad assignments (a String
 * into a Number field) and unknown fields (cannot find symbol) — exactly what the
 * Apex compiler catches against the org schema — while SOQL/DML and dynamic
 * get/put keep operating on the same backing store.
 */
final class SObjectClassGen {

    /** Java source for one sObject class (default package, like the user's classes). */
    static String generate(String sobjectType, Map<String, String> fields, Set<String> typed) {
        StringBuilder sb = new StringBuilder();
        sb.append("import alloyx.runtime.SObject;\n");
        sb.append("import alloyx.runtime.SObjectField;\n");
        sb.append("import alloyx.runtime.List;\n");
        sb.append("import alloyx.runtime.Decimal;\n");
        sb.append("import alloyx.runtime.Date;\n");
        sb.append("import alloyx.runtime.Datetime;\n");
        sb.append("import alloyx.runtime.Time;\n\n");

        sb.append("@SuppressWarnings(\"unchecked\")\n");
        sb.append("public class ").append(sobjectType).append(" extends SObject {\n");
        sb.append("    public ").append(sobjectType).append("() { super(\"")
          .append(sobjectType).append("\"); }\n");
        // wrap an existing record (a SOQL/Trigger row) as this typed sObject,
        // carrying its field values over
        sb.append("    public ").append(sobjectType).append("(SObject src) { super(\"")
          .append(sobjectType).append("\"); getFields().putAll(src.getFields()); }\n");

        // typed wrappers for the SOQL/runtime boundary: query returns List<SObject>,
        // these re-type it so `List<Account> a = [SELECT...]` and single-row reads work
        sb.append("    public static List<").append(sobjectType).append("> many(List<SObject> rows) {\n");
        sb.append("        List<").append(sobjectType).append("> out = new List<>();\n");
        sb.append("        for (SObject r : rows) out.add(new ").append(sobjectType).append("(r));\n");
        sb.append("        return out;\n    }\n");
        sb.append("    public static ").append(sobjectType).append(" one(List<SObject> rows) {\n");
        sb.append("        return rows.isEmpty() ? null : new ").append(sobjectType).append("(rows.get(0));\n    }\n");

        for (Map.Entry<String, String> e : fields.entrySet()) {
            String field = e.getKey();
            String javaType = javaType(e.getValue(), typed);
            // A static FIELD-TOKEN named exactly like the field's canonical API name, so the
            // selector pattern `Item__c.Id` resolves to a Schema.SObjectField (its toString() is
            // the field API name, used to build SOQL column lists). Distinct from the get<Field>/
            // set<Field> accessors below, and the base SObject declares no member by a field name,
            // so there's no collision. The Apex type access Item__c.id folds to this canonical
            // token in the emitter (case-insensitive, like the field accessors).
            sb.append("    public static final SObjectField ").append(field)
              .append(" = new SObjectField(\"").append(sobjectType).append("\", \"")
              .append(field).append("\");\n");
            // typed accessors over the inherited dynamic map (single backing store).
            // Numeric reads go through SObject.asX so a SOQL value that came back as a
            // different numeric runtime type (Integer "100" for a Decimal currency field,
            // Long for an Integer field) is coerced to the declared type instead of CCE'ing
            // on a raw cast; everything else keeps the plain cast.
            sb.append("    public ").append(javaType).append(" get").append(field)
              .append("() { return ").append(getterExpr(javaType, field)).append("; }\n");
            sb.append("    public void set").append(field).append('(').append(javaType)
              .append(" value) { put(\"").append(field).append("\", value); }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Getter body: numeric types coerce via SObject.asX, the rest take a plain cast. */
    private static String getterExpr(String javaType, String field) {
        String read = "get(\"" + field + "\")";
        return switch (javaType) {
            case "Decimal" -> "SObject.asDecimal(" + read + ")";
            case "Integer" -> "SObject.asInteger(" + read + ")";
            case "Long" -> "SObject.asLong(" + read + ")";
            case "Double" -> "SObject.asDouble(" + read + ")";
            default -> "(" + javaType + ") " + read;
        };
    }

    private static String javaType(String apexType, Set<String> typed) {
        return switch (apexType) {
            case "Integer" -> "Integer";
            case "Long" -> "Long";
            case "Double" -> "Double";
            case "Boolean" -> "Boolean";
            case "Decimal" -> "Decimal";
            case "Date" -> "Date";
            case "Datetime" -> "Datetime";
            case "Time" -> "Time";
            case "Object", "Blob" -> "Object";
            case "String", "Id" -> "String";
            // a relationship (apexType is the related sObject's name): type it only if
            // that object is itself generated, else keep it a dynamic record
            default -> typed.contains(apexType) ? apexType : "SObject";
        };
    }
}
