// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import alloyx.runtime.SchemaProvider;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central static type inference for expressions: ONE source of truth the Transpiler's
 * emission paths share, instead of the divergent shallow detectors that used to each
 * inspect a few node shapes (and silently disagree). {@link #typeOf} returns the APEX
 * type name when confidently known ("String", "Integer", "Decimal", "Boolean", a class
 * or sObject name) or {@code null} when unknown — and {@code null} is load-bearing: the
 * Transpiler keeps its existing fall-through (default emission) for an untyped result, so
 * a wider/narrower-but-uncertain answer never changes behavior. Stay CONSERVATIVE: only
 * return a type we can stand behind.
 *
 * <p>This class is pure typing (single responsibility) — it never emits Java. It shares
 * the Transpiler's live {@code locals} map and reads the same schema/member context, so
 * its answers track the exact scope the emitter is in (locals are pushed/popped there).
 */
final class ExprTyper {
    // Apex scalar types that are NOT sObject relationships (used to tell a relationship
    // field from a value field when consulting the schema).
    private static final Set<String> SCALARS = Set.of("String", "Integer", "Long", "Double",
        "Decimal", "Boolean", "Date", "Datetime", "Time", "Id", "Blob", "Object", "void");

    // String instance methods (Java's own, emitted as raw `.m()`) whose result is itself a
    // String — enough to type a chained `s.trim().split(...)` target. Kept conservative: only
    // methods we are certain return String. size()/length() handled separately (-> Integer).
    private static final Set<String> STRING_TO_STRING = Set.of(
        "trim", "toLowerCase", "toUpperCase", "substring", "replace", "replaceAll",
        "replaceFirst", "abbreviate", "capitalize", "deleteWhitespace", "left", "right",
        "normalizeSpace", "reverse", "strip", "stripStart", "stripEnd",
        "substringAfter", "substringBefore", "substringBetween", "removeStart", "removeEnd");

    // String instance methods whose result is an Integer.
    private static final Set<String> STRING_TO_INT = Set.of(
        "length", "size", "indexOf", "lastIndexOf", "countMatches", "compareTo");

    // String instance methods whose result is a Boolean.
    private static final Set<String> STRING_TO_BOOL = Set.of(
        "contains", "startsWith", "endsWith", "equals", "equalsIgnoreCase",
        "isAllUpperCase", "isAllLowerCase", "isAlpha", "isNumeric", "isBlank", "matches");

    private static final Set<String> ARITH = Set.of("+", "-", "*", "/");

    private final SchemaProvider schema;
    private final Set<String> typedSObjects;
    private final Set<String> userClasses;
    private final Set<String> innerTypes;
    // class (simple name) -> (lowercase member -> declared Apex type). Covers fields, method
    // return types and params of the class(es) the Transpiler currently holds, so a same-class
    // method call / field read can be typed. Names alone live in `memberIndex`; this adds types.
    private final Map<String, Map<String, String>> memberTypes;
    // shared, LIVE references to the Transpiler's scope: the locals map it mutates as it
    // descends, and a view of the current class body's fields (swapped per inner/outer body).
    private final Map<String, String> locals;
    private final java.util.function.Supplier<Map<String, String>> fieldTypes;

    ExprTyper(SchemaProvider schema, Set<String> typedSObjects, Set<String> userClasses,
              Set<String> innerTypes, Map<String, Map<String, String>> memberTypes,
              Map<String, String> locals, java.util.function.Supplier<Map<String, String>> fieldTypes) {
        this.schema = schema;
        this.typedSObjects = typedSObjects;
        this.userClasses = userClasses;
        this.innerTypes = innerTypes;
        this.memberTypes = memberTypes;
        this.locals = locals;
        this.fieldTypes = fieldTypes;
    }

    /**
     * The Apex type of {@code e} when confidently known, else {@code null}. The base
     * (un-generic) Apex type name is returned for scalars/classes; for a collection the
     * full {@code List<T>} text, so an index read can recover the element type.
     */
    String typeOf(Expr e) {
        return switch (e) {
            case Str ignored -> "String";
            case Num ignored -> "Integer";
            case DecimalLit ignored -> "Decimal";
            case Bool ignored -> "Boolean";
            case Null ignored -> null; // null literal: no useful static type
            case Name n -> typeOfName(n);
            case Prop p -> typeOfProp(p);
            case MethodCall mc -> typeOfMethodCall(mc);
            case Ternary t -> {
                String a = typeOf(t.then());
                String b = typeOf(t.els());
                yield a != null && a.equals(b) ? a : null; // both branches agree -> that type
            }
            case Binary b -> typeOfBinary(b);
            case Unary u -> u.op().equals("!") ? "Boolean" : typeOf(u.operand());
            case Postfix p -> typeOf(p.operand());
            case Cast c -> c.type();
            case New nw -> nw.type();
            case SObjectLit so -> so.type();
            case ListLit l -> l.type();
            case Index ix -> elementType(typeOf(ix.target())); // List<T> -> T
            default -> null;
        };
    }

    private String typeOfName(Name n) {
        if (n.ident().equals("this")) {
            return null;
        }
        String t = locals.get(n.ident());
        if (t != null) {
            return t;
        }
        // case-insensitive local/param/field match (Apex), mirroring canonicalName
        for (Map.Entry<String, String> en : locals.entrySet()) {
            if (en.getKey().equalsIgnoreCase(n.ident())) {
                return en.getValue();
            }
        }
        return null;
    }

    private String typeOfProp(Prop p) {
        // this.<field>: fields are copied into locals, but `this` resolves to null, so look
        // the field up in the current class body's field view.
        if (p.target() instanceof Name tn && tn.ident().equals("this")) {
            Map<String, String> f = fieldTypes.get();
            String ft = f == null ? null : f.get(p.name());
            return ft != null ? ft : memberType(currentClassOf(), p.name());
        }
        // sObject field via the schema describe
        String parent = sObjectTypeOf(p.target());
        if (parent != null) {
            String ft = schema.fieldType(parent, p.name());
            // a relationship hop yields the related sObject (non-scalar); a value field its type
            return ft;
        }
        // user-class field via the member type index, keyed by the target's declared class
        String declared = typeOf(p.target());
        return memberType(base(declared), p.name());
    }

    private String typeOfMethodCall(MethodCall mc) {
        String target = typeOf(mc.target());
        // String instance methods we know the result type of (chained typing).
        if ("String".equals(target)) {
            String name = mc.name();
            if (STRING_TO_STRING.contains(name)) {
                return "String";
            }
            if (STRING_TO_INT.contains(name)) {
                return "Integer";
            }
            if (STRING_TO_BOOL.contains(name)) {
                return "Boolean";
            }
        }
        // user-class method via the member type index (return type), keyed by the target's
        // class — or the current class for a bare/this-rooted call.
        String owner = base(target);
        if (owner == null && (mc.target() instanceof Name tn && tn.ident().equals("this"))) {
            owner = currentClassOf();
        }
        return memberType(owner, mc.name());
    }

    private String typeOfBinary(Binary b) {
        String op = b.op();
        if (op.equals("&&") || op.equals("||")
            || op.equals("==") || op.equals("!=")
            || op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
            return "Boolean";
        }
        if (op.equals("+") && (isString(b.left()) || isString(b.right()))) {
            return "String"; // either side String -> concatenation
        }
        if (ARITH.contains(op)) {
            // any Decimal side widens the whole expression to Decimal (Apex arithmetic rule)
            if (isDecimal(b.left()) || isDecimal(b.right())) {
                return "Decimal";
            }
            // both Integer -> Integer (keeps primitive arithmetic primitive)
            if (isInteger(b.left()) && isInteger(b.right())) {
                return "Integer";
            }
        }
        return null;
    }

    // --- thin predicates the Transpiler's emission paths consume (one source of truth)

    boolean isString(Expr e) {
        // Id behaves as a String in Apex expressions (concat, comparisons); the old detector
        // folded it via mapType(Id) -> String, so the typer must too or `id + decimal`
        // would mis-route into Decimal arithmetic.
        return isScalar(typeOf(e), "String") || isScalar(typeOf(e), "Id");
    }

    boolean isDecimal(Expr e) {
        return isScalar(typeOf(e), "Decimal");
    }

    boolean isInteger(Expr e) {
        return isScalar(typeOf(e), "Integer");
    }

    /** Whether the expected Apex type is Decimal and the value an Integer (needs widening). */
    boolean needsDecimalWiden(String expectedApexType, Expr value) {
        return expectedApexType != null
            && base(expectedApexType).equalsIgnoreCase("Decimal")
            && isInteger(value);
    }

    // Apex type names are case-insensitive (decimal == Decimal). Compare an inferred type
    // against a canonical scalar without the cost/risk of folding non-scalar class names.
    private static boolean isScalar(String inferred, String canonical) {
        return inferred != null && base(inferred).equalsIgnoreCase(canonical);
    }

    // --- helpers

    // the sObject API name an expression evaluates to (Account, ...), via the schema; null
    // otherwise. Mirrors the Transpiler's own sObjectTypeOf so field typing stays aligned.
    private String sObjectTypeOf(Expr e) {
        String t = typeOf(e);
        if (t == null) {
            // this.<field> sObject: typeOf already consults fieldTypes, but a bare `this` is null
            if (e instanceof Prop p && p.target() instanceof Name tn && tn.ident().equals("this")) {
                Map<String, String> f = fieldTypes.get();
                t = f == null ? null : f.get(p.name());
            }
        }
        if (t == null) {
            return null;
        }
        String b = base(t);
        return isSObjectName(b) ? b : null;
    }

    private boolean isSObjectName(String base) {
        return base != null && (typedSObjects.contains(base)
            || (!SCALARS.contains(base) && !userClasses.contains(base)
                && !innerTypes.contains(base) && !COLLECTION.contains(base)
                && schema.fieldType(base, "Id") != null));
    }

    private static final Set<String> COLLECTION = Set.of("List", "Set", "Map");

    // look up a member's declared Apex type on a known user class (case-insensitive member)
    private String memberType(String klass, String member) {
        if (klass == null) {
            return null;
        }
        Map<String, String> m = memberTypes.get(klass);
        if (m == null && klass.contains(".")) {
            m = memberTypes.get(klass.substring(klass.lastIndexOf('.') + 1)); // Outer.Inner -> Inner
        }
        return m == null ? null : m.get(member.toLowerCase(Locale.ROOT));
    }

    private String currentClassOf() {
        return currentClass;
    }

    // The class whose body is being emitted — set by the Transpiler before each class body so a
    // `this`-rooted method/field lookup resolves against the right entry of memberTypes.
    String currentClass;

    private static String elementType(String type) {
        if (type == null) {
            return null;
        }
        int lt = type.indexOf('<');
        if (lt < 0) {
            return null;
        }
        // List<T> -> T ; Map<K,V> -> V (the value, like Index get on a Map)
        String inner = type.substring(lt + 1, type.lastIndexOf('>'));
        int depth = 0, last = 0;
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(inner.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(inner.substring(last));
        return parts.get(parts.size() - 1).trim();
    }

    private static String base(String type) {
        if (type == null) {
            return null;
        }
        int lt = type.indexOf('<');
        return (lt >= 0 ? type.substring(0, lt) : type).replace("[]", "");
    }
}
