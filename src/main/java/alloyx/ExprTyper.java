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
    // lowercased built-in/native/runtime type names the Transpiler already recognizes (Integer,
    // Decimal, Database, Test, ...). Feeds isKnownTypeName so a bare ident that NAMES a type is
    // never mistaken for an inherited field (e.g. `Pay.split(x)` where a superclass field is `pay`).
    private final Set<String> builtinTypeNames;
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
              Map<String, String> locals, java.util.function.Supplier<Map<String, String>> fieldTypes,
              Set<String> builtinTypeNames) {
        this.schema = schema;
        this.typedSObjects = typedSObjects;
        this.userClasses = userClasses;
        this.innerTypes = innerTypes;
        this.memberTypes = memberTypes;
        this.locals = locals;
        this.fieldTypes = fieldTypes;
        this.builtinTypeNames = builtinTypeNames;
    }

    // Whether `ident` names a KNOWN type — a user class, an inner type, a generated typed sObject,
    // or a built-in/native/runtime type (case-insensitive, as Apex is) — rather than a value. The
    // bare-name field fallback must NOT treat such an ident as an inherited field: when a static
    // call's target (`Pay.split(x)`) collides with an inherited field name (`pay`), resolving it as
    // the field mis-routes emission. Apex prefers a genuine variable over a class name, but locals
    // already win before this check; the inherited-field-vs-class collision is rare enough that
    // preferring the CLASS (return null -> default static-call emission) is the conservative choice.
    boolean isKnownTypeName(String ident) {
        if (ident == null) {
            return false;
        }
        if (userClasses.contains(ident) || innerTypes.contains(ident) || typedSObjects.contains(ident)) {
            return true;
        }
        return builtinTypeNames.contains(ident.toLowerCase(Locale.ROOT));
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
        // a bare ident that NAMES a type (Pay in `Pay.split(x)`) is the target of a static call, not
        // an inherited field — even if the current class inherits a field of the same name. Don't
        // resolve it as a field; null falls through to the default static-call emission (the
        // conservative choice: prefer the class on the rare field-vs-class collision).
        if (isKnownTypeName(n.ident())) {
            return null;
        }
        // an INHERITED field read without `this.`: the current body's own fields live in
        // locals, a superclass's don't — resolve through the member index's extends walk.
        // Locals shadow this on purpose (checked above), matching Apex scoping.
        return memberType(currentClassOf(), n.ident());
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
            if (ft != null) {
                return ft;
            }
            // a child-relationship collection (ord.OrderItems__r): not a field of the parent
            // (it lives in the parent's childRelationships describe, which the synced schema
            // doesn't store), so we can't know the child sObject. Type it List<SObject> — the
            // List<SObject> covariance + dynamic getSObjects() routing carry it from here.
            if (isChildRelationship(parent, p.name())) {
                return "List<SObject>";
            }
            return null;
        }
        // a STATIC field-token reference `Item__c.Id`: target is a bare TYPED sObject TYPE name
        // (not a local/instance) and the member is a described field -> the field token's type,
        // so a `new List<Schema.SObjectField>{ Item__c.Id, ... }` literal accepts each element.
        if (p.target() instanceof Name tn && !locals.containsKey(tn.ident())
                && typedSObjects.contains(tn.ident())
                && schema.fieldType(tn.ident(), p.name()) != null) {
            return "Schema.SObjectField";
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
        // builtin collection methods on a PARAMETERIZED List/Set/Map: resolve the result from the
        // target's generic args (map.get(k) -> V, list.get(i) -> T, m.values() -> List<V>, ...).
        // This is the big real-world win: `someMap.get(id).Field__c` / `list.get(0).method()`
        // now type their result so the sObject field/method hop routes correctly.
        String collResult = typeOfCollectionMethod(target, mc.name());
        if (collResult != null) {
            return collResult;
        }
        // user-class method via the member type index (return type), keyed by the target's
        // class — or the current class for a bare/this-rooted call.
        String owner = base(target);
        if (owner == null && (mc.target() instanceof Name tn && tn.ident().equals("this"))) {
            owner = currentClassOf();
        }
        return memberType(owner, mc.name());
    }

    // Apex builtin collection methods are case-insensitive, like the rest of Apex. Grouped by the
    // result they produce so the lookup stays a single case-insensitive membership test per group.
    private static final Set<String> SIZE_METHODS = lower("size");
    private static final Set<String> BOOL_METHODS = lower("isEmpty", "contains", "containsKey");
    // get/remove on a List return the element T; on a Map return the value V (handled by caller).
    private static final Set<String> ELEMENT_METHODS = lower("get", "remove");
    private static final Set<String> CLONE_METHODS = lower("clone", "deepClone");

    private static Set<String> lower(String... names) {
        Set<String> s = new java.util.HashSet<>();
        for (String n : names) {
            s.add(n.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    // The Apex result type of a builtin collection method invoked on `target` (the target's full
    // type text, e.g. "Map<Id,Account>"), or null when not a known collection method on a
    // parameterized collection. CONSERVATIVE: a raw List/Map (no generics, or an Object generic
    // we can't stand behind) yields null so emission falls through unchanged. Method names match
    // case-insensitively (Apex). Only types what the runtime List/Set/Map classes actually return.
    private String typeOfCollectionMethod(String target, String name) {
        if (target == null) {
            return null;
        }
        String b = base(target);
        boolean isMap = "Map".equalsIgnoreCase(b);
        boolean isList = "List".equalsIgnoreCase(b);
        boolean isSet = "Set".equalsIgnoreCase(b);
        if (!isMap && !isList && !isSet) {
            return null;
        }
        java.util.List<String> args = genericArgs(target);
        if (args.isEmpty()) {
            return null; // raw List/Map -> conservative null (no new typing)
        }
        String m = name.toLowerCase(Locale.ROOT);
        // shared across all three collections (and exact in the runtime: HashMap/ArrayList/HashSet)
        if (SIZE_METHODS.contains(m)) {
            return "Integer";
        }
        if (BOOL_METHODS.contains(m)) {
            return "Boolean";
        }
        if (CLONE_METHODS.contains(m)) {
            return target; // clone()/deepClone() keep the same collection type
        }
        if (isMap) {
            String k = args.get(0).trim();
            String v = args.get(args.size() - 1).trim();
            // Map.get/remove -> V (runtime HashMap.get/remove both return V).
            // Map.put returns the prior V (runtime extends HashMap, whose put returns V — Apex's
            // own put is void, but we type only what the runtime actually returns).
            if (ELEMENT_METHODS.contains(m) || "put".equals(m)) {
                return v;
            }
            if ("values".equals(m)) {
                return "List<" + v + ">"; // runtime Map.values() -> a List view, typed by V
            }
            if ("keyset".equals(m)) {
                return "Set<" + k + ">";
            }
            return null;
        }
        // List/Set share an element type T (the single generic arg)
        String t = args.get(0).trim();
        if (isList && ELEMENT_METHODS.contains(m)) {
            // List.get(i) / List.remove(i) -> the removed/returned element T
            return t;
        }
        return null;
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

    // A `<X>__r` member on a typed sObject that the parent's field map does NOT carry — i.e. a
    // CHILD-relationship collection (the field map holds parent-ref `__r` names typed as the related
    // sObject; a child collection lives only in the parent's childRelationships describe, which the
    // synced schema doesn't store). The caller already established `schema.fieldType` returned null
    // for this name, so any `__r` here is a child collection. Case-insensitive `__r` suffix, as Apex.
    boolean isChildRelationship(String parent, String member) {
        return parent != null && member != null
            && member.regionMatches(true, member.length() - 3, "__r", 0, 3);
    }

    private boolean isSObjectName(String base) {
        return base != null && (typedSObjects.contains(base)
            || (!SCALARS.contains(base) && !userClasses.contains(base)
                && !innerTypes.contains(base) && !COLLECTION.contains(base)
                && schema.fieldType(base, "Id") != null));
    }

    private static final Set<String> COLLECTION = Set.of("List", "Set", "Map");

    // Look up a member's declared Apex type on a known user class (case-insensitive member),
    // following the `extends` chain when the member isn't declared on the class itself: a param
    // q:EventQueue extends EventRecord reading q.config (config declared on EventRecord) resolves.
    // Heritage is stored by Transpiler.populateMemberTypes under the reserved EXTENDS_KEY (the
    // superclass simple name). The `seen` set guards against an inheritance cycle in malformed
    // input (A extends B extends A) so the walk always terminates. Package-private so the
    // Transpiler's emission paths share this ONE walk (DRY) instead of mirroring it.
    String memberType(String klass, String member) {
        return memberType(klass, member, new java.util.HashSet<>());
    }

    private String memberType(String klass, String member, Set<String> seen) {
        Map<String, String> m = entryFor(klass);
        if (m == null || !seen.add(simpleName(klass))) {
            return null; // unknown class, or a cycle already visited -> stop (no infinite loop)
        }
        String own = m.get(member.toLowerCase(Locale.ROOT));
        // an ambiguity-poisoned entry (a param position, or a contested same-simple-name inner
        // alias) is load-bearing null: fall through to default emission, never a wrong type.
        if (own != null) {
            return Transpiler.isAmbiguous(own) ? null : own;
        }
        String sup = m.get(Transpiler.EXTENDS_KEY);
        if (sup == null) {
            return null;
        }
        // Heritage is stored by SIMPLE name. Walking from an inner to its super, prefer the
        // SAME-OUTER qualified entry (OuterA.Sup) — that's Apex's sibling-inner scoping, and it's
        // the only one left when the bare `Sup` alias is poisoned by a same-simple-name inner in a
        // different outer. The outer is the klass's own qualifier, or — when we reached the entry by
        // its simple alias (`Sub`, not `OuterA.Sub`) — the alias's recorded qualified owner.
        String origin = klass.contains(".") ? klass : m.get(Transpiler.QUALIFIED_OWNER);
        return memberType(qualifySuper(origin, sup), member, seen);
    }

    // Given the qualified origin (Outer.Inner) of the entry we're walking from and a bare super
    // simple name, return the SAME-OUTER qualified super (Outer.Sup) when that entry exists; else
    // the super unchanged (a top-level super resolves via its simple name as before).
    private String qualifySuper(String origin, String sup) {
        if (origin != null && origin.contains(".") && !sup.contains(".")) {
            String outer = origin.substring(0, origin.lastIndexOf('.'));
            String qualified = outer + "." + sup;
            if (memberTypes.containsKey(qualified)) {
                return qualified;
            }
        }
        return sup;
    }

    // The member-type entry for a class, by simple name (Outer.Inner -> Inner), or null.
    private Map<String, String> entryFor(String klass) {
        if (klass == null) {
            return null;
        }
        Map<String, String> m = memberTypes.get(klass);
        if (m == null && klass.contains(".")) {
            m = memberTypes.get(simpleName(klass)); // Outer.Inner -> Inner
        }
        return m;
    }

    private static String simpleName(String klass) {
        return klass != null && klass.contains(".")
            ? klass.substring(klass.lastIndexOf('.') + 1) : klass;
    }

    private String currentClassOf() {
        return currentClass;
    }

    // The class whose body is being emitted — set by the Transpiler before each class body so a
    // `this`-rooted method/field lookup resolves against the right entry of memberTypes.
    String currentClass;

    private static String elementType(String type) {
        // List<T> -> T ; Map<K,V> -> V (the value, like Index get on a Map): the LAST generic arg.
        java.util.List<String> args = genericArgs(type);
        return args.isEmpty() ? null : args.get(args.size() - 1).trim();
    }

    // The top-level generic arguments of a parameterized type, depth-aware (so Map<Id,List<Account>>
    // splits into ["Id", "List<Account>"], not on the inner comma). Empty for a raw/non-generic type.
    // One source of truth for both the value extractor (elementType) and the Map-key/value reader.
    private static java.util.List<String> genericArgs(String type) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (type == null) {
            return parts;
        }
        int lt = type.indexOf('<');
        if (lt < 0) {
            return parts;
        }
        String inner = type.substring(lt + 1, type.lastIndexOf('>'));
        int depth = 0, last = 0;
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
        return parts;
    }

    private static String base(String type) {
        if (type == null) {
            return null;
        }
        int lt = type.indexOf('<');
        return (lt >= 0 ? type.substring(0, lt) : type).replace("[]", "");
    }
}
