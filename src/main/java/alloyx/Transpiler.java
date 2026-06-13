// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import alloyx.runtime.SchemaProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Apex AST -> Java source (a string, kept on disk so it's inspectable and reads
 * almost identically to the original Apex).
 *
 * Java does the heavy lifting that Python couldn't: bare field/method names and
 * {@code this} resolve natively, so we mostly emit names as-is. We map Apex
 * types to Java ({@code Account} -> {@code SObject}, {@code Decimal} ->
 * {@code BigDecimal}), use {@code var} for locals, and translate Apex value
 * equality ({@code ==}) to {@code Objects.equals} (Java {@code ==} is reference).
 */
final class Transpiler {
    // lineMap: generated-Java line -> originating Apex line (empty unless built for `check`)
    record Result(String className, String source, java.util.Map<Integer, Integer> lineMap) {
        Result(String className, String source) {
            this(className, source, java.util.Map.of());
        }
    }

    private static final String IMPORTS = String.join("\n",
        "import alloyx.runtime.System;",
        "import alloyx.runtime.Assert;",
        "import alloyx.runtime.Safe;",
        "import alloyx.runtime.List;",
        "import alloyx.runtime.Set;",
        "import alloyx.runtime.Map;",
        "import alloyx.runtime.Math;",
        "import alloyx.runtime.Database;",
        "import alloyx.runtime.SObject;",
        "import alloyx.runtime.ApexException;",
        "import alloyx.runtime.Decimal;",
        "import alloyx.runtime.Date;",
        "import alloyx.runtime.Datetime;",
        "import alloyx.runtime.Time;",
        "import alloyx.runtime.JSON;",
        "import alloyx.runtime.Label;",
        "import alloyx.runtime.UserInfo;",
        "import alloyx.runtime.Strings;",
        "import alloyx.runtime.Type;",
        "import alloyx.runtime.SObjectType;",
        "import alloyx.runtime.SObjectField;",
        "import alloyx.runtime.DescribeSObjectResult;",
        "import alloyx.runtime.Pattern;",
        "import alloyx.runtime.Matcher;",
        "import alloyx.runtime.LoggingLevel;",
        "import alloyx.runtime.Limits;",
        "import alloyx.runtime.Http;",
        "import alloyx.runtime.HttpRequest;",
        "import alloyx.runtime.HttpResponse;",
        "import alloyx.runtime.XmlStreamWriter;",
        "import alloyx.runtime.Messaging;",
        "import alloyx.runtime.RestRequest;",
        "import alloyx.runtime.RestResponse;",
        "import alloyx.runtime.RestContext;",
        "import alloyx.runtime.JSONGenerator;",
        "import alloyx.runtime.PicklistEntry;",
        "import alloyx.runtime.RecordTypeInfo;",
        "import alloyx.runtime.ChildRelationship;",
        "import alloyx.runtime.DescribeFieldResult;",
        "import alloyx.runtime.Blob;",
        "import alloyx.runtime.EncodingUtil;",
        "import alloyx.runtime.Trigger;",
        "import alloyx.runtime.Test;",
        "import alloyx.runtime.ApexPages;",
        "import alloyx.runtime.PageReference;",
        "import alloyx.runtime.AggregateResult;",
        "import alloyx.runtime.ConnectApi;",
        "import alloyx.runtime.Schema;",
        "import alloyx.runtime.Schedulable;",
        "import alloyx.runtime.SchedulableContext;",
        "import alloyx.runtime.Queueable;",
        "import alloyx.runtime.QueueableContext;",
        "import alloyx.runtime.Comparable;",
        "import alloyx.runtime.Iterable;",
        "import alloyx.runtime.Iterator;",
        "import alloyx.runtime.dom.Document;",
        "import alloyx.runtime.dom.XmlNode;",
        "import alloyx.runtime.dom.XmlNodeType;");

    private static final Set<String> JAVA_SAME =
        Set.of("Integer", "Long", "Boolean", "String", "Object", "Double", "void");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Map");

    // Whether a base type name is an Apex collection (List/Set/Map), matched case-INSENSITIVELY —
    // Apex type names aren't case sensitive, so a `list<contact>` is the same type as `List<Contact>`.
    private static boolean isCollectionType(String base) {
        return base != null && COLLECTIONS.contains(capitalize(base));
    }

    // Capitalize the first letter (list -> List), leaving the rest untouched: enough to fold a
    // lowercase Apex collection keyword onto its canonical COLLECTIONS spelling.
    private static String capitalize(String s) {
        return s.isEmpty() ? s
            : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
    // Apex Schema namespace types backed by runtime classes (not dynamic sObjects)
    private static final Set<String> SCHEMA_TYPES =
        Set.of("SObjectType", "DescribeSObjectResult", "SObjectField");
    // native Apex System types backed by runtime classes (not dynamic sObjects). The async/
    // schedulable platform interfaces (Schedulable, Queueable, their contexts) and the sortable/
    // iterable contracts (Comparable, Iterable, Iterator) live here too: they're real Apex
    // interfaces backed by runtime interfaces, so `implements Schedulable` maps to a real Java
    // interface instead of collapsing to the dynamic SObject ("interface expected here").
    private static final Set<String> RUNTIME_TYPES = Set.of("Pattern", "Matcher", "LoggingLevel", "Limits",
        "Http", "HttpRequest", "HttpResponse", "Blob", "EncodingUtil", "Trigger", "Test", "Assert",
        "ApexPages", "PageReference", "AggregateResult",
        "Schedulable", "SchedulableContext", "Queueable", "QueueableContext",
        "Comparable", "Iterable", "Iterator",
        // RC3 runtime stubs: local-behaving (XmlStreamWriter builds XML, JSONGenerator builds JSON,
        // Rest* carry request/response data, Messaging.* compose email), or describe tokens whose
        // org-only members degrade (PicklistEntry/RecordTypeInfo/ChildRelationship/DescribeFieldResult).
        "XmlStreamWriter", "JSONGenerator", "RestRequest", "RestResponse", "RestContext",
        "PicklistEntry", "RecordTypeInfo", "ChildRelationship", "DescribeFieldResult",
        // System.Type: already a static-call target via BUILTINS; registering it here lets a
        // DECLARED `Type t = Type.forName(...)` map to the runtime class instead of SObject
        "Type");
    // Database namespace types: nested classes/interfaces on the runtime Database, kept qualified.
    // Batchable<T> + its context/locator + the Stateful/AllowsCallouts/RaisesPlatformEvents markers
    // are real Apex interfaces, so `implements Database.Batchable<sObject>` maps to a real Java
    // interface (nested on Database) instead of collapsing to the dynamic SObject.
    private static final Set<String> DATABASE_TYPES =
        Set.of("SaveResult", "UpsertResult", "DeleteResult", "Error",
            "Batchable", "BatchableContext", "QueryLocator", "QueryLocatorIterator",
            "Stateful", "AllowsCallouts", "RaisesPlatformEvents",
            "Savepoint", "LeadConvert", "LeadConvertResult");
    // Recognized runtime/Database types that carry a generic parameter to PRESERVE (Batchable<T>,
    // Iterable<T>, Iterator<T>) — their <...> must be mapped through, not stripped like the other
    // qualified runtime types. Names are simple (Database. prefix already removed when consulted).
    private static final Set<String> GENERIC_RUNTIME_TYPES =
        Set.of("Batchable", "Iterable", "Iterator");
    // ApexPages namespace nested types: nested classes/enum on the runtime ApexPages, kept qualified
    private static final Set<String> APEXPAGES_TYPES =
        Set.of("Severity", "Message", "StandardController", "StandardSetController");
    // Messaging namespace nested types: nested classes on the runtime Messaging, kept qualified
    // (mirrors DATABASE_TYPES). `Messaging.SingleEmailMessage`/`EmailFileAttachment` carry email
    // data locally; the send results mirror the Database DML-result inspection-degrades shape.
    private static final Set<String> MESSAGING_TYPES =
        Set.of("SingleEmailMessage", "EmailFileAttachment", "SendEmailResult", "SendEmailError");
    // Apex is case-insensitive, so HTTPRequest == HttpRequest and blob == Blob; map a lowercased
    // native type name back to its canonical runtime class.
    private static final java.util.Map<String, String> RUNTIME_CANON = lowerIndex(RUNTIME_TYPES);

    private static java.util.Map<String, String> lowerIndex(Set<String> names) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String n : names) m.put(n.toLowerCase(java.util.Locale.ROOT), n);
        return m;
    }

    // Recognized PLATFORM namespace roots a static call/property can target — the runtime classes
    // (System, Test, JSON, Database, ...) that BUILTINS / RUNTIME_TYPES back. Keyed by the lowercased
    // simple name (Apex is case-insensitive) so a `System.<X>` chain can be recognized as rooting at
    // a real platform TYPE. Also drives reflective method-name case folding (foldStaticMethod):
    // `system.test.isrunningtest()` / `Database.executebatch(...)` must fold to the canonical Java
    // method name. Label is included so `System.Label.X` / `Label.X` resolves (see emitProp).
    private static final java.util.Map<String, Class<?>> PLATFORM_CLASSES = platformClasses();

    private static java.util.Map<String, Class<?>> platformClasses() {
        java.util.Map<String, Class<?>> m = new java.util.HashMap<>();
        for (Class<?> c : List.of(
                alloyx.runtime.System.class, alloyx.runtime.Math.class, alloyx.runtime.Database.class,
                alloyx.runtime.JSON.class, alloyx.runtime.UserInfo.class, alloyx.runtime.Test.class,
                alloyx.runtime.Type.class, alloyx.runtime.Limits.class, alloyx.runtime.Assert.class,
                alloyx.runtime.ApexPages.class, alloyx.runtime.Schema.class, alloyx.runtime.Label.class,
                alloyx.runtime.EncodingUtil.class, alloyx.runtime.Blob.class, alloyx.runtime.Http.class,
                alloyx.runtime.Trigger.class, alloyx.runtime.PageReference.class,
                // RC3: Messaging.sendEmail(...) statics + RestContext.request/response static holders
                // root here so a bare-namespace call/access is recognized (not a field/SObject).
                alloyx.runtime.Messaging.class, alloyx.runtime.RestContext.class,
                // Strings backs the Apex `String` class's STATICS (String.valueOf/join/isBlank...): the
                // String-static router emits `Strings.<m>`, so its method names must fold here too
                // (`String.valueof(x)` -> `Strings.valueOf(x)`), like every other platform class.
                alloyx.runtime.Strings.class)) {
            m.put(c.getSimpleName().toLowerCase(java.util.Locale.ROOT), c);
        }
        return java.util.Map.copyOf(m);
    }

    // The canonically-cased Java name of a static method on a recognized platform class, matched
    // case-INSENSITIVELY (Apex is). Lets `Database.executebatch(...)` / `Test.isrunningtest()` fold
    // to the real Java method (`executeBatch`/`isRunningTest`). Falls back to lowerFirst (the prior
    // Date.ValueOf -> valueOf behavior) when the type isn't a known platform class or no method
    // matches — never less correct than before, since the exact-cased name still folds to itself.
    private static String foldStaticMethod(String typeSimpleName, String method) {
        Class<?> c = PLATFORM_CLASSES.get(typeSimpleName.toLowerCase(java.util.Locale.ROOT));
        if (c != null) {
            for (java.lang.reflect.Method jm : c.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(jm.getModifiers())
                        && jm.getName().equalsIgnoreCase(method)) {
                    return jm.getName();
                }
            }
        }
        return lowerFirst(method);
    }

    // The runtime/JDK-backed class a scalar/collection Apex BASE type name is emitted as (the receiver
    // of an instance call). Keyed by the lowercased Apex base name (Apex is case-insensitive). Only
    // types whose instance methods land on a real Java class are listed — a dynamic SObject / user
    // class is intentionally absent, so an instance call on one is never case-folded (its method name
    // is left exactly as written). String/Id -> java.lang.String (instance String methods stay on the
    // real String; the Strings helper carries only statics + the few Java lacks). Mirrors mapType's
    // runtime-type decisions, but yields the Class itself for reflective case folding.
    private static final java.util.Map<String, Class<?>> INSTANCE_RECEIVER_CLASSES =
        instanceReceiverClasses();

    private static java.util.Map<String, Class<?>> instanceReceiverClasses() {
        java.util.Map<String, Class<?>> m = new java.util.HashMap<>();
        m.put("string", String.class);
        m.put("id", String.class); // Id is a String at runtime
        m.put("list", alloyx.runtime.List.class);
        m.put("set", alloyx.runtime.Set.class);
        m.put("map", alloyx.runtime.Map.class);
        m.put("decimal", alloyx.runtime.Decimal.class);
        m.put("date", alloyx.runtime.Date.class);
        m.put("datetime", alloyx.runtime.Datetime.class);
        m.put("time", alloyx.runtime.Time.class);
        m.put("blob", alloyx.runtime.Blob.class);
        return java.util.Map.copyOf(m);
    }

    // Per-class cache of (lowercased method name -> canonical Java instance-method name) for the
    // runtime/JDK receiver classes. Built lazily by reflecting the class's public INSTANCE methods,
    // so the reflective scan runs once per class, not once per call. A name absent from the map has
    // no instance method of that spelling on the class (it falls through to the verbatim name).
    private static final java.util.Map<Class<?>, java.util.Map<String, String>> INSTANCE_METHOD_FOLD =
        new java.util.concurrent.ConcurrentHashMap<>();

    // The canonically-cased Java instance-method name on `receiverClass` matching `method`
    // case-INSENSITIVELY (Apex is), or `method` unchanged when no instance method matches. An
    // exact-case match short-circuits (the index keys are lowercased, but the stored value is the
    // canonical spelling, so a correctly-cased call resolves to itself with no extra work).
    private static String foldInstanceMethod(Class<?> receiverClass, String method) {
        java.util.Map<String, String> index = INSTANCE_METHOD_FOLD.computeIfAbsent(
            receiverClass, Transpiler::buildInstanceFoldIndex);
        String folded = index.get(method.toLowerCase(java.util.Locale.ROOT));
        return folded != null ? folded : method; // unknown name: leave exactly as written
    }

    private static java.util.Map<String, String> buildInstanceFoldIndex(Class<?> c) {
        java.util.Map<String, String> index = new java.util.HashMap<>();
        for (java.lang.reflect.Method jm : c.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(jm.getModifiers())) {
                // first canonical spelling wins; an exact-cased Apex call still folds to itself
                index.putIfAbsent(jm.getName().toLowerCase(java.util.Locale.ROOT), jm.getName());
            }
        }
        return index;
    }
    // Apex trigger context members -> the runtime Trigger stub's fields (case-insensitive,
    // as Apex is); `new` is a Java keyword so it maps to newRecords.
    private static final java.util.Map<String, String> TRIGGER_MEMBERS = java.util.Map.ofEntries(
        java.util.Map.entry("new", "newRecords"), java.util.Map.entry("old", "old"),
        java.util.Map.entry("newmap", "newMap"), java.util.Map.entry("oldmap", "oldMap"),
        java.util.Map.entry("size", "size"), java.util.Map.entry("isexecuting", "isExecuting"),
        java.util.Map.entry("isinsert", "isInsert"), java.util.Map.entry("isupdate", "isUpdate"),
        java.util.Map.entry("isdelete", "isDelete"), java.util.Map.entry("isundelete", "isUndelete"),
        java.util.Map.entry("isbefore", "isBefore"), java.util.Map.entry("isafter", "isAfter"));
    // native Apex enums whose members are referenced case-insensitively (LoggingLevel.Info ==
    // LoggingLevel.INFO); the runtime backs them with canonical UPPER_CASE constants
    private static final Set<String> RUNTIME_ENUMS = Set.of("LoggingLevel");
    // String instance methods Java's String lacks (or returns a different type for):
    // routed to the Strings helper as Strings.method(theString, ...)
    private static final Set<String> APEX_STRING_METHODS = Set.of(
        "split", "countMatches", "substringAfter", "substringBefore", "substringBetween",
        "removeStart", "removeEnd");

    // Apex is case-insensitive; map a lowercased built-in name to its canonical form
    // so `decimal`/`Date.ValueOf`/`system.debug` resolve to the right Java symbol.
    private static final java.util.Map<String, String> BUILTINS = java.util.Map.ofEntries(
        java.util.Map.entry("integer", "Integer"), java.util.Map.entry("long", "Long"),
        java.util.Map.entry("double", "Double"), java.util.Map.entry("boolean", "Boolean"),
        java.util.Map.entry("string", "String"), java.util.Map.entry("object", "Object"),
        java.util.Map.entry("decimal", "Decimal"), java.util.Map.entry("date", "Date"),
        // `id` (lowercase) is the same scalar as `Id`; folds to Id so mapType then returns String.
        java.util.Map.entry("id", "Id"),
        java.util.Map.entry("datetime", "Datetime"), java.util.Map.entry("time", "Time"),
        java.util.Map.entry("list", "List"), java.util.Map.entry("set", "Set"),
        java.util.Map.entry("map", "Map"), java.util.Map.entry("system", "System"),
        java.util.Map.entry("math", "Math"), java.util.Map.entry("database", "Database"),
        java.util.Map.entry("json", "JSON"), java.util.Map.entry("userinfo", "UserInfo"),
        // Assert is the modern test-assertion class; routed here so a case-insensitive Apex call
        // (assert.areEqual) folds to the runtime Assert. A user class named Assert shadows it
        // (guarded above), matching Apex's "your own type wins" precedence.
        java.util.Map.entry("assert", "Assert"));

    private static final Set<String> SCALARS = Set.of("String", "Integer", "Long", "Double",
        "Decimal", "Boolean", "Date", "Datetime", "Time", "Id", "Blob", "Object", "void");

    // Every built-in/native/runtime/schema/Database type name the Transpiler recognizes, LOWERCASED
    // (Apex is case-insensitive). Built once from the existing constants (DRY) and handed to the
    // typer's isKnownTypeName, so a bare ident that names a type is never mistaken for an inherited
    // field. The BUILTINS values (System, Math, Database, JSON, ...) cover the namespace roots a
    // static call can target; their keys are already the lowercased scalar/collection names.
    private static final Set<String> BUILTIN_TYPE_NAMES = buildBuiltinTypeNames();

    private static Set<String> buildBuiltinTypeNames() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Set<String> group : List.of(SCALARS, COLLECTIONS, RUNTIME_TYPES, SCHEMA_TYPES, DATABASE_TYPES)) {
            for (String n : group) s.add(n.toLowerCase(java.util.Locale.ROOT));
        }
        for (String k : BUILTINS.keySet()) s.add(k); // already lowercase
        for (String v : BUILTINS.values()) s.add(v.toLowerCase(java.util.Locale.ROOT));
        // platform namespace roots that aren't scalars/runtime types (Messaging, RestContext, ...):
        // their lowercased simple names so a bare `Messaging.sendEmail(...)` root is a KNOWN type,
        // never mistaken for an inherited field. Keys are already lowercase.
        s.addAll(PLATFORM_CLASSES.keySet());
        return java.util.Set.copyOf(s);
    }

    private final Set<String> userClasses;
    private final SchemaProvider schema;
    // sObjects with a generated typed class (described via sync); empty -> everything
    // stays the generic dynamic SObject, byte-for-byte the pre-typing behavior. Wrapped in a
    // case-INSENSITIVE view (Apex isn't case-sensitive): the set holds only the canonical casing,
    // so a type written in another case (`account acc;`) must still resolve and emit `Account`.
    private final TypedSObjects typedSObjects;
    // light type tracking so sObject field access (a.Name) becomes a.get("Name").
    // fieldTypes is swapped per class body (outer vs inner) as emission descends.
    private java.util.Map<String, String> fieldTypes = new java.util.HashMap<>();
    private final java.util.Map<String, String> locals = new java.util.HashMap<>();
    // names of locals/params reassigned anywhere in the current method body (rebuilt per method).
    // Java lambdas can only capture effectively-final variables, but Apex reassigns locals freely,
    // so a safe-nav lowering whose access references a reassigned name must avoid the Safe.nav
    // lambda and fall back to a ternary (see safeNav). Conservative: when in doubt, treat as
    // reassigned (correctness over the single-evaluation property).
    private final Set<String> reassignedLocals = new java.util.HashSet<>();
    // names of the current method's PARAMS and declared LOCALS (rebuilt per method). A typed sObject
    // literal lowers to a double-brace init (`new Item__c(){{ setName(name); }}`) where `this` and a
    // bare field name rebind to the anon subclass; a bare arg-value name that's a current-class FIELD
    // must be qualified to the enclosing instance — but ONLY when no param/local of this name shadows
    // it (Apex: a param/local wins over a field). This set is that shadow guard. See qualifyEnclosing.
    private final Set<String> methodScopeLocals = new java.util.HashSet<>();
    // whether the method whose body is being emitted is STATIC. A static method has no enclosing
    // instance, so qualifyEnclosing must never emit `<Cls>.this` inside it (javac: "non-static
    // variable this cannot be referenced from a static context") — a bare current-class field read
    // there is necessarily a STATIC field, qualified as `<Cls>.<name>`. Rebuilt per method.
    private boolean currentMethodStatic = false;
    // names of the current class body's STATIC fields (rebuilt per class body, in fieldTypes order).
    // A static field is qualified `<Cls>.<name>` in BOTH static and instance methods (correct Java
    // for a static; also escapes the typed-literal anon block's token shadowing) — see qualifyEnclosing.
    private Set<String> staticFields = new java.util.HashSet<>();
    private String currentReturnType = "void";
    // monotonic id for the synthetic lambda parameter that carries an evaluated-once safe-nav
    // target (a?.b -> Safe.nav(a, __sn0 -> __sn0.b)); a counter keeps nested chains' params distinct.
    private int safeNavId = 0;
    // monotonic id for the synthetic temp a `switch on` lowers its subject into (evaluated once),
    // so nested switches don't reuse the same temp name.
    private int switchId = 0;
    // inner (nested) class names — both simple (Inner) and qualified (Outer.Inner) —
    // so references resolve as a user type, never the fallback dynamic SObject
    private final Set<String> innerTypes = new java.util.HashSet<>();
    // the top-level class being emitted: the qualifier for its inner types. Lets a field/local whose
    // declared type names an inner be QUALIFIED (Outer.Inner) in the locals/fieldTypes view, mirroring
    // the member-type index, so `this.event`/a local typed by an inner that collides with a global
    // sObject name resolves to the inner — not the sObject. Set in emitClass (one top-level class).
    private String outerName;
    // source map inputs/outputs: stmt->Apex line (from the parser) and the
    // generated-Java line -> Apex line accumulated while emitting
    private java.util.Map<Stmt, Integer> stmtLines = java.util.Map.of();
    private final java.util.Map<Integer, Integer> lineMap = new java.util.TreeMap<>();
    // class name -> (lowercase field -> canonical field), so a qualified field access
    // resolves case-insensitively (Apex: incomingItem.Name == incomingItem.name)
    private final java.util.Map<String, java.util.Map<String, String>> memberIndex;
    // class name -> (lowercase member -> declared Apex type): fields, method return types and
    // param types of EVERY class in the compile set (seeded from the global index), so the central
    // typer can type a CROSS-class field read / method call and coerce a Decimal-param argument —
    // not just the one class being emitted. The current class's entries are re-seeded (and so win)
    // in indexMemberTypes when emission starts.
    private final java.util.Map<String, java.util.Map<String, String>> memberTypes;
    // the one source of truth for static expression typing (see ExprTyper); shares this
    // Transpiler's live locals map and a view of the current class body's fields.
    private final ExprTyper typer;

    private Transpiler(Set<String> userClasses, SchemaProvider schema, Set<String> typedSObjects,
                       java.util.Map<String, java.util.Map<String, String>> memberIndex,
                       java.util.Map<String, java.util.Map<String, String>> memberTypes) {
        this.userClasses = userClasses;
        this.schema = schema;
        // wrap the canonical-cased set in a case-insensitive view (Apex isn't case-sensitive) and
        // share the SAME instance with the typer, so both resolve type names identically.
        this.typedSObjects = new TypedSObjects(typedSObjects);
        this.memberIndex = memberIndex;
        // Copy the global member-type index so the per-class re-seed (indexMemberTypes) can replace
        // the current class's entry without mutating the shared map other Transpilers read from.
        this.memberTypes = new java.util.HashMap<>(memberTypes);
        this.typer = new ExprTyper(schema, this.typedSObjects, userClasses, innerTypes, this.memberTypes,
            locals, () -> fieldTypes, BUILTIN_TYPE_NAMES);
    }

    private boolean isSObject(Expr e) {
        return sObjectTypeOf(e) != null;
    }

    /** This sObject has a generated typed class (so field access is a typed getter/setter). */
    private boolean isTyped(String base) {
        return typedSObjects.has(base);
    }

    /**
     * The CANONICAL-cased name of a typed sObject (so emitted Java links against the generated
     * `class Account`), or the input unchanged when it isn't a typed sObject. Apex is case-insensitive,
     * so a `new account(...)` / `account[]` written in any case must emit the org casing.
     */
    private String typedName(String base) {
        String canon = typedSObjects.canonical(base);
        return canon != null ? canon : base;
    }

    /** This name denotes an sObject — either a generated typed one or the generic SObject. */
    private boolean isSObjectName(String base) {
        return typedSObjects.has(base) || mapType(base).equals("SObject");
    }

    // the sObject API name an expression evaluates to (Account, Contact, ...), or null.
    // Relationship hops (a.Owner) are resolved via the schema describe when available.
    private String sObjectTypeOf(Expr e) {
        return switch (e) {
            case Name n -> {
                // canonicalName first: Apex idents are case-insensitive, so `Account.Name` with a
                // local `account` in scope is a VARIABLE read (vars win over types) — resolving the
                // spelling here lets the typed-getter path fire instead of leaking the raw ident
                // onto the generated class (where it would bind the static field token).
                String ident = ExprTyper.isThisRef(n.ident()) ? n.ident() : canonicalName(n.ident());
                String t = ExprTyper.isThisRef(ident) ? null : locals.get(ident);
                if (t == null && !ExprTyper.isThisRef(ident) && !typer.isKnownTypeName(ident)) {
                    // an inherited field read without `this.`: not in locals (only the current
                    // body's own fields are) — resolve through the member index's extends walk.
                    // A bare ident that NAMES a type (the target of a static call) is NOT an
                    // inherited field, even when a superclass declares a field of the same name;
                    // skip the field walk so the static-call emission isn't mis-routed.
                    t = memberType(typer.currentClass, ident);
                }
                yield t != null && isSObjectName(base(t)) ? base(t) : null;
            }
            case Cast c -> isSObjectName(base(c.type())) ? base(c.type()) : null;
            case New nw -> isSObjectName(base(nw.type())) ? base(nw.type()) : null;
            case SObjectLit so -> base(so.type());
            case Prop p -> {
                // this.<field> (or the synthetic <Cls>.this.<field> a typed-literal rewrite emits):
                // resolve to the field's own declared type. Fields are copied into `locals`, but the
                // target `this` resolves to null above, so this.lead.CNPJ__c would miss the getter
                // without this. An INHERITED field isn't in locals (only the current body's fields
                // are), so fall back to the member-type index, which walks the `extends` chain.
                if (p.target() instanceof Name tn && ExprTyper.isThisRef(tn.ident())) {
                    String ft0 = locals.get(p.name());
                    if (ft0 == null) {
                        ft0 = memberType(typer.currentClass, p.name());
                    }
                    yield ft0 != null && isSObjectName(base(ft0)) ? base(ft0) : null;
                }
                String parent = sObjectTypeOf(p.target());
                if (parent == null) {
                    // The target is a user-class instance whose field is sObject-typed
                    // (rec.config where rec:EventRecord and config:Config__c). The schema
                    // chain can't see it; the member-type index can (now cross-class).
                    yield userClassMemberSObject(p);
                }
                String ft = schema.fieldType(parent, p.name());
                yield ft != null && !SCALARS.contains(ft) ? ft : null; // a relationship field
            }
            // collection-method / index results (map.get(id).Field, list.get(0).Field,
            // m.values().get(0).Field): the typer now resolves these from the collection's
            // generics, so an sObject element routes its field hop through the typed getter.
            case MethodCall mc -> {
                String t = typer.typeOf(mc);
                yield t != null && isSObjectName(base(t)) ? base(t) : null;
            }
            case Index ix -> {
                String t = typer.typeOf(ix);
                yield t != null && isSObjectName(base(t)) ? base(t) : null;
            }
            default -> null;
        };
    }

    // The sObject API name of `target.field` when `target` is a known user-class instance and
    // `field` is declared an sObject type on it (via the cross-class member-type index), else null.
    // This is what lets a chained read/write `rec.config.Endpoint__c` resolve its sObject parent
    // when the field lives on ANOTHER class in the compilation.
    private String userClassMemberSObject(Prop p) {
        String declared = typer.typeOf(p.target());
        if (declared == null || !userClasses.contains(base(declared))) {
            return null;
        }
        String ft = memberType(base(declared), p.name());
        return ft != null && isSObjectName(base(ft)) ? base(ft) : null;
    }

    // The declared Apex type of `target.field` when `target` is a known user-class instance and
    // `field` is one of its members (via the cross-class member-type index), else null. Drives the
    // Decimal-widen on an Assign-to-Prop whose target is another class's field (cart.cost = 333)
    // OR the current class's own field via `this` (this.cost = 333).
    private String propFieldType(Prop p) {
        // this.<field>: typeOf(this) is null (by design), so the cross-class branch below can't
        // see it. Resolve through the typer's this.<field> path, which consults the current class
        // body's field view and walks the extends chain — and, crucially, ignores locals, so a
        // same-named Integer local that shadows a Decimal FIELD doesn't mistype `this.field`.
        // (The original bail returned null here, which the `declared == null` guard already did;
        // it just pre-empted this resolution — restoring it is what lets `this.field = 0` widen.)
        if (p.target() instanceof Name tn && ExprTyper.isThisRef(tn.ident())) {
            return typer.typeOf(p);
        }
        String declared = typer.typeOf(p.target());
        if (declared == null || !userClasses.contains(base(declared))) {
            return null;
        }
        return memberType(base(declared), p.name());
    }

    // The declared Apex type of a member (case-insensitive) on a known user class. Delegates to
    // the central typer's ONE lookup (DRY) — which walks the `extends` chain to an inherited
    // member and guards against inheritance cycles — so the emission paths and the typer never
    // disagree on which member resolves to which type.
    private String memberType(String klass, String member) {
        return typer.memberType(klass, member);
    }

    static Result transpile(ClassDecl cls) {
        return transpile(cls, Set.of(cls.name()));
    }

    static Result transpile(ClassDecl cls, Set<String> userClasses) {
        return transpile(cls, userClasses, (o, f) -> null); // no schema -> untyped field access
    }

    static Result transpile(ClassDecl cls, Set<String> userClasses, SchemaProvider schema) {
        return transpile(cls, userClasses, schema, Set.of());
    }

    static Result transpile(ClassDecl cls, Set<String> userClasses, SchemaProvider schema,
                            Set<String> typedSObjects) {
        return transpile(cls, userClasses, schema, typedSObjects, java.util.Map.of());
    }

    // Retrocompat: callers without a global member-type index build a single-class one from `cls`,
    // preserving today's behavior (the typer sees only the class being emitted). The full
    // cross-class index comes in via the overload below, fed by Workspace.memberTypes(allDecls).
    static Result transpile(ClassDecl cls, Set<String> userClasses, SchemaProvider schema,
                            Set<String> typedSObjects,
                            java.util.Map<String, java.util.Map<String, String>> memberIndex) {
        return transpile(cls, userClasses, schema, typedSObjects, memberIndex, singleMemberTypes(cls));
    }

    static Result transpile(ClassDecl cls, Set<String> userClasses, SchemaProvider schema,
                            Set<String> typedSObjects,
                            java.util.Map<String, java.util.Map<String, String>> memberIndex,
                            java.util.Map<String, java.util.Map<String, String>> memberTypes) {
        return new Transpiler(userClasses, schema, typedSObjects, memberIndex, memberTypes).emitClass(cls);
    }

    // Same as transpile, but builds the source map (generated-Java line -> Apex line)
    // from the parser's statement lines. Used by `allx check`.
    static Result transpileWithLines(ClassDecl cls, Set<String> userClasses, SchemaProvider schema,
                                     Set<String> typedSObjects, java.util.Map<Stmt, Integer> stmtLines,
                                     java.util.Map<String, java.util.Map<String, String>> memberIndex,
                                     java.util.Map<String, java.util.Map<String, String>> memberTypes) {
        Transpiler t = new Transpiler(userClasses, schema, typedSObjects, memberIndex, memberTypes);
        t.stmtLines = stmtLines;
        return t.emitClass(cls);
    }

    // The member-type index for a SINGLE class (+ its inners) — the retrocompat default when no
    // cross-class index is supplied. Same population routine as the global one, over one decl.
    private static java.util.Map<String, java.util.Map<String, String>> singleMemberTypes(ClassDecl cls) {
        java.util.Map<String, java.util.Map<String, String>> m = new java.util.HashMap<>();
        populateMemberTypes(cls, m);
        return m;
    }

    private Result emitClass(ClassDecl cls) {
        this.outerName = cls.name();
        registerInnerTypes(cls);
        indexMemberTypes(cls);
        StringBuilder sb = new StringBuilder();
        sb.append(IMPORTS).append("\n\n");
        // the Apex->Java generics bridging (raw/Object casts) is intentional; hide the
        // unchecked notes — they're noise to the end user, not actionable
        sb.append("@SuppressWarnings(\"unchecked\")\n");
        sb.append("public ");
        emitClassBody(cls, java.util.Map.of(), "", sb);
        return new Result(cls.name(), sb.toString(), lineMap);
    }

    // The `extends`/`implements` clause. A class extends its superclass and implements its
    // interfaces; a Java interface extends them all (Apex `interface I extends J` maps straight).
    private String emitTypeRelations(ClassDecl cls, boolean iface) {
        java.util.List<String> ext = new java.util.ArrayList<>();
        if (cls.superclass() != null) ext.add(mapType(cls.superclass()));
        if (iface) {
            for (String i : cls.interfaces()) ext.add(mapType(i));
            return ext.isEmpty() ? "" : " extends " + String.join(", ", ext);
        }
        StringBuilder s = new StringBuilder();
        if (!ext.isEmpty()) s.append(" extends ").append(String.join(", ", ext));
        if (!cls.interfaces().isEmpty()) {
            java.util.List<String> impl = new java.util.ArrayList<>();
            for (String i : cls.interfaces()) impl.add(mapType(i));
            s.append(" implements ").append(String.join(", ", impl));
        }
        return s.toString();
    }

    // Record every inner class under both its simple and Outer.Inner name (Apex nests
    // only one level), so mapType resolves them to a real user type, not an sObject.
    private void registerInnerTypes(ClassDecl cls) {
        for (ClassDecl inner : cls.inners()) {
            innerTypes.add(inner.name());
            innerTypes.add(cls.name() + "." + inner.name());
        }
    }

    // Qualify a declared field/local/param type whose simple base names one of the CURRENT top-level
    // class's inner types (Outer.Inner) so the locals/fieldTypes view matches the member-type index.
    // Without this, `this.event` / a local typed by an inner colliding with a global sObject name (a
    // bare `Event`) would resolve via locals to the std sObject, routing the next hop dynamically.
    // Mirrors populateMemberTypes.qualifyOwnInner, applied to the emission-time type views; the same
    // per-class precedence — a class with no such inner keeps the bare name (-> the std sObject).
    private String qualifyInnerType(String rawType) {
        if (rawType == null || outerName == null) {
            return rawType;
        }
        String b = base(rawType);
        if (b == null || b.contains(".") || !innerTypes.contains(b)) {
            return rawType;
        }
        int lt = rawType.indexOf('<');
        return lt < 0 ? outerName + "." + b : outerName + "." + b + rawType.substring(lt);
    }

    // Seed this Transpiler's member-type index with the class being emitted (+ its inners) on top
    // of whatever was pre-seeded from the global index. The CURRENT class's entries must WIN over a
    // pre-seeded copy (same simple name across the compile set is possible — Apex has no packages),
    // so the current class's map is rebuilt fresh rather than left as the shared global instance.
    private void indexMemberTypes(ClassDecl cls) {
        memberTypes.put(cls.name(), new java.util.HashMap<>());
        populateMemberTypes(cls, memberTypes);
    }

    // Reserved member-index keys (no Apex member can collide: they aren't valid identifiers).
    // EXTENDS_KEY records the class's superclass simple name so a member lookup can walk the
    // inheritance chain; CTOR_PARAM is the per-position constructor param type (the ctor itself
    // carries no return type, so `(new)#i` is the only way a `new Foo(args)` site recovers it).
    static final String EXTENDS_KEY = "(extends)";
    // Ambiguity sentinel (two flavors, both load-bearing → null at lookup, no coercion / no typing):
    //  - at the PARAM level: a position contested by overloads with DIFFERENT types at the same
    //    index (Pay(Decimal) vs Pay(Integer)). Coercing then would silently bind the WRONG Java
    //    overload; we'd rather not coerce and let the literal pick Apex's overload natively.
    //  - at the CLASS level: a simple inner-class key contested by a SECOND outer's same-simple-name
    //    inner (OuterA.Helper vs OuterB.Helper). Merging would mis-type a cross-class read; we poison
    //    the simple entry so unqualified lookups fall through (qualified Outer.Inner stays correct).
    static final String AMBIGUOUS = "(ambiguous)";
    private static String ctorParamKey(int i) {
        return "(new)#" + i;
    }

    /** Whether a member-type lookup hit the ambiguity sentinel (so no coercion / no typing fires). */
    static boolean isAmbiguous(String type) {
        return AMBIGUOUS.equals(type);
    }

    // Register a param-position type with compare-and-mark (NOT putIfAbsent): first writer wins, a
    // later SAME type is a true overload and stays usable, but a later DIFFERENT type at the same
    // position poisons the key with AMBIGUOUS so the call site won't coerce into the wrong overload.
    private static void markParam(java.util.Map<String, String> m, String key, String type) {
        String prev = m.putIfAbsent(key, type);
        if (prev != null && !prev.equals(type)) {
            m.put(key, AMBIGUOUS); // overloads disagree at this position → no coercion
        }
    }

    // Index a class's members (fields + method return types + param types) by lowercase name,
    // keyed by class simple name, into `into`, so the central typer can type a field read / method
    // call and coerce a Decimal-param call argument. Param types use a `(name)#i` key to avoid
    // colliding with a same-named field/method; the lookup the typer needs by name is the return
    // type. The superclass simple name is recorded under EXTENDS_KEY so the lookups can climb the
    // chain to an inherited member. Static so Workspace can build the SAME index over EVERY class in
    // the compile set (cross-class typing).
    static void populateMemberTypes(ClassDecl cls,
            java.util.Map<String, java.util.Map<String, String>> into) {
        // The file-scope inner names of THIS top-level class — used to qualify a member type that
        // names one of them (see qualifyOwnInner). Apex nests only one level, so the outer's inners
        // are the full set visible to the outer and to every sibling inner.
        java.util.Set<String> innerNames = new java.util.HashSet<>();
        for (ClassDecl inner : cls.inners()) {
            innerNames.add(inner.name());
        }
        populateMemberTypes(cls, into, null, cls.name(), innerNames);
    }

    // `qualified`, when non-null, is the Outer.Inner name an inner class is ALSO indexed under (a
    // collision-free key), in addition to its simple name. The qualified entry is authoritative for
    // that inner; the simple entry is a retrocompat alias for unqualified references and is POISONED
    // if a second, different outer contributes an inner with the same simple name (see AMBIGUOUS).
    //
    // `outer`/`innerNames` carry the file-scope owner: when a recorded member type names an inner of
    // THIS outer (e.g. a field `Event event` where Event is `outer`.Event), it is stored QUALIFIED
    // (`outer.Event`) so consumers never face the ambiguity of the bare name colliding with a global
    // sObject. Apex scoping: inside a class, its own inner type names shadow global/sObject names for
    // member DECLARATIONS — this mirrors that precedence, applied per-owner (a DIFFERENT class with a
    // bare `Event` field, having no such inner, still records the bare name -> the std sObject).
    private static void populateMemberTypes(ClassDecl cls,
            java.util.Map<String, java.util.Map<String, String>> into, String qualified,
            String outer, java.util.Set<String> innerNames) {
        // Build the class's own members into a fresh map first, then publish it under its key(s).
        // (For an outer/top-level class this map IS its entry; for an inner it's the authoritative
        // qualified entry AND the source the simple alias mirrors when uncontested.)
        java.util.Map<String, String> m =
            into.computeIfAbsent(qualified != null ? qualified : cls.name(), k -> new java.util.HashMap<>());
        // superclass heritage: stored by SIMPLE name (Outer.Inner -> Inner), the same resolution
        // the rest of the index uses, so the chain walk resolves against the indexed entries.
        if (cls.superclass() != null) {
            String sup = base(cls.superclass());
            if (sup.contains(".")) {
                sup = sup.substring(sup.lastIndexOf('.') + 1);
            }
            m.putIfAbsent(EXTENDS_KEY, sup);
        }
        for (Field f : cls.fields()) {
            m.putIfAbsent(f.name().toLowerCase(java.util.Locale.ROOT), qualifyOwnInner(f.type(), outer, innerNames));
        }
        for (MethodDecl md : cls.methods()) {
            String mn = md.name().toLowerCase(java.util.Locale.ROOT);
            boolean isCtor = md.name().equals(cls.name());
            // ctor has no return type; skip so `new Foo()` typing stays via New
            if (!isCtor) {
                m.putIfAbsent(mn, qualifyOwnInner(md.returnType(), outer, innerNames));
            }
            // params keyed by position so a call site can recover the declared param type;
            // a constructor's go under `(new)#i` so `new Foo(args)` can coerce a Decimal arg.
            // markParam (not putIfAbsent) so overloads disagreeing at a position poison the key.
            for (int i = 0; i < md.params().size(); i++) {
                String pt = qualifyOwnInner(md.params().get(i).type(), outer, innerNames);
                markParam(m, "(" + mn + ")#" + i, pt);
                if (isCtor) {
                    markParam(m, ctorParamKey(i), pt);
                }
            }
        }
        // For an inner, also publish under the simple-name alias — mirroring its members for
        // retrocompat unqualified references — UNLESS a different outer already claimed that simple
        // name, in which case the alias is poisoned (a wrong merge would mis-type a cross-class read).
        if (qualified != null) {
            aliasInner(into, cls.name(), qualified, m);
        }
        // inners: indexed under BOTH the collision-free Outer.Inner key and the simple alias. The
        // file-scope owner (outer/innerNames) is threaded unchanged so a sibling inner's member that
        // names another inner of the SAME outer is qualified the same way the outer's own members are.
        for (ClassDecl inner : cls.inners()) {
            populateMemberTypes(inner, into, cls.name() + "." + inner.name(), outer, innerNames);
        }
    }

    // When `rawType`'s simple base names an inner class OF THE OWNING OUTER (`outer`), return the
    // QUALIFIED `outer.Base` (preserving any generic suffix), so a member typed by an inner name that
    // collides with a global/sObject name resolves to the INNER — Apex's per-class shadowing for
    // member declarations. Already-qualified or non-inner names pass through unchanged. Name-agnostic:
    // it only consults the actual inner-name set of the class being indexed, never a fixed list.
    private static String qualifyOwnInner(String rawType, String outer, java.util.Set<String> innerNames) {
        if (rawType == null || outer == null) {
            return rawType;
        }
        String b = base(rawType);
        if (b == null || b.contains(".") || !innerNames.contains(b)) {
            return rawType; // already qualified, or not one of this outer's inners
        }
        // preserve a generic suffix (e.g. List<Event> stays a List; only the base is qualified here —
        // an inner-typed ELEMENT keeps its own ambiguity, out of scope for this member-base fix).
        int lt = rawType.indexOf('<');
        return lt < 0 ? outer + "." + b : outer + "." + b + rawType.substring(lt);
    }

    // Reserved marker the simple inner-class alias carries to record which qualified inner owns it —
    // so a second, different outer's same-simple-name inner is detected and poisons the alias, and
    // so the typer can recover the outer to qualify a sibling-inner super. Not a valid Apex
    // identifier, so it never collides with a real member.
    static final String QUALIFIED_OWNER = "(qualified-owner)";

    // Publish an inner's members under its simple-name alias. First qualified inner to claim the
    // simple name owns it (its members are mirrored). A second, DIFFERENT qualified inner with the
    // same simple name poisons the alias (cleared + AMBIGUOUS marker) so unqualified lookups miss and
    // fall through, rather than reading a wrong merge. The SAME inner re-registering (idempotent
    // re-seed) is a no-op refresh of its own alias.
    private static void aliasInner(java.util.Map<String, java.util.Map<String, String>> into,
            String simpleName, String qualified, java.util.Map<String, String> members) {
        java.util.Map<String, String> alias =
            into.computeIfAbsent(simpleName, k -> new java.util.HashMap<>());
        String owner = alias.get(QUALIFIED_OWNER);
        if (owner != null && !owner.equals(qualified)) {
            alias.clear(); // contested by a different outer's inner → ambiguous
            alias.put(AMBIGUOUS, AMBIGUOUS);
            return;
        }
        if (alias.get(AMBIGUOUS) != null) {
            return; // already poisoned by an earlier contest; stay poisoned
        }
        alias.clear();
        alias.putAll(members); // mirror the authoritative qualified entry
        alias.put(QUALIFIED_OWNER, qualified);
    }

    // The declared Apex type of param #index of a bare same-class method (by lowercase method
    // name), or null. Lets the call site coerce a Decimal-typed argument (Apex widens Integer
    // -> Decimal; Java needs it explicit).
    private String paramTypeOf(String method, int index) {
        return paramTypeOf(typer.currentClass, method, index);
    }

    // Same, but for a method on a known user class (e.g. an explicit `obj.m(...)` where obj's
    // static type is in our member index). Unknown class/method -> null (no coercion). A position
    // contested by disagreeing overloads is AMBIGUOUS -> also null, so we never coerce into the
    // wrong Java overload.
    private String paramTypeOf(String klass, String method, int index) {
        if (klass == null) {
            return null;
        }
        java.util.Map<String, String> m = memberTypes.get(base(klass));
        if (m == null && base(klass).contains(".")) {
            m = memberTypes.get(base(klass).substring(base(klass).lastIndexOf('.') + 1));
        }
        String t = m == null ? null : m.get("(" + method.toLowerCase(java.util.Locale.ROOT) + ")#" + index);
        return isAmbiguous(t) ? null : t;
    }

    // Emit a class body from its header onward at the given indent. The caller writes the
    // leading "public "/"static " modifier. `outerStatics` are the enclosing class's static
    // fields, visible to an inner class (which, like a Java static nested class, sees only
    // the outer statics — Apex inner classes carry no outer instance).
    private void emitClassBody(ClassDecl cls, java.util.Map<String, String> outerStatics,
                               String indent, StringBuilder sb) {
        // an enum is a flat constant list: emit and return (no fields/methods/inners)
        if (cls.kind().equals("enum")) {
            sb.append("enum ").append(cls.name()).append(" { ")
              .append(String.join(", ", cls.enumValues())).append(" }\n");
            return;
        }
        boolean iface = cls.kind().equals("interface");

        java.util.Map<String, String> myFields = new java.util.LinkedHashMap<>(outerStatics);
        // qualify a field typed by an inner of this top-level class (Outer.Inner) so the typer's
        // this.<field> / locals view agrees with the member-type index — see qualifyInnerType.
        for (Field f : cls.fields()) myFields.put(f.name(), qualifyInnerType(f.type()));
        this.fieldTypes = myFields;
        // this class body's OWN static fields (by name): qualified `<Cls>.<name>` in qualifyEnclosing.
        // Saved/restored like fieldTypes since inner bodies are emitted within the same Transpiler.
        Set<String> prevStaticFields = this.staticFields;
        Set<String> myStaticFields = new java.util.HashSet<>();
        for (Field f : cls.fields()) if (f.isStatic()) myStaticFields.add(f.name());
        this.staticFields = myStaticFields;
        String prevClass = typer.currentClass;
        typer.currentClass = cls.name(); // `this`-rooted member typing resolves against this body

        // Apex `abstract class` maps straight to a Java abstract class (so it can hold
        // bodyless abstract methods and can't be instantiated). `virtual` has no Java
        // analogue and is dropped — every Java method is overridable already.
        if (!iface && cls.isAbstract()) sb.append("abstract ");
        sb.append(iface ? "interface " : "class ").append(cls.name());
        sb.append(emitTypeRelations(cls, iface));
        sb.append(" {\n");
        String member = indent + "    ";
        String stmt = member + "    ";

        for (Field f : cls.fields()) {
            sb.append(member);
            if (f.isStatic()) sb.append("static ");
            sb.append(mapType(f.type())).append(' ').append(f.name());
            // Apex widens an Integer initializer to a Decimal field (private Decimal total = 0);
            // Java needs it explicit, so coerce through the same path the locals/params use.
            if (f.init() != null) sb.append(" = ").append(coerceNumeric(f.type(), f.init()));
            sb.append(";\n");
        }

        // Apex gives every Exception subclass the built-in constructors (no-arg and
        // (String)) REGARDLESS of any custom ones declared — so `new MyException('x')`
        // works even when the class declares only, say, a (HttpResponse) constructor.
        // Inject the built-ins not already declared (matched by Java param signature),
        // so a custom constructor no longer suppresses them.
        boolean isException = cls.superclass() != null
            && (cls.superclass().equals("Exception") || cls.superclass().endsWith("Exception"));
        if (!iface && isException) {
            java.util.Set<String> ctorSigs = new java.util.HashSet<>();
            for (MethodDecl m : cls.methods()) {
                if (m.name().equals(cls.name())) {
                    ctorSigs.add(m.params().stream().map(p -> mapType(p.type()))
                        .collect(java.util.stream.Collectors.joining(",")));
                }
            }
            sb.append('\n');
            if (ctorSigs.add("")) {
                sb.append(member).append("public ").append(cls.name()).append("() { super(); }\n");
            }
            if (ctorSigs.add("String")) {
                sb.append(member).append("public ").append(cls.name())
                  .append("(String message) { super(message); }\n");
            }
        }

        // Apex overloads by distinct sObject types (generate(Account)/generate(Contact))
        // collapse to the same Java signature once everything is SObject — keep the first.
        java.util.Set<String> sigs = new java.util.HashSet<>();
        for (MethodDecl m : cls.methods()) {
            if (!sigs.add(javaSig(m))) continue;
            sb.append('\n');
            if (iface) {
                // interface method: signature only (implicitly public abstract, no body)
                sb.append(member).append(mapType(m.returnType())).append(' ')
                  .append(m.name()).append('(');
                for (int i = 0; i < m.params().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Param p = m.params().get(i);
                    sb.append(mapType(p.type())).append(' ').append(p.name());
                }
                sb.append(");\n");
                continue;
            }
            boolean isCtor = m.name().equals(cls.name());
            locals.clear();
            locals.putAll(fieldTypes);
            // qualify a param typed by this class's inner (Outer.Inner) the same as fields, so a
            // method whose param/locals are inner-typed (colliding with a global sObject) types right.
            for (Param p : m.params()) locals.put(p.name(), qualifyInnerType(p.type()));
            reassignedLocals.clear();
            collectReassigned(m.body(), reassignedLocals);
            // param + declared-local names of this method: the shadow guard for the typed-literal
            // arg-value rewrite (a bare field name is qualified only when no local/param shadows it).
            methodScopeLocals.clear();
            for (Param p : m.params()) methodScopeLocals.add(p.name());
            collectDeclaredLocals(m.body(), methodScopeLocals);
            // a static method has no enclosing instance — qualifyEnclosing must not emit `<Cls>.this`.
            currentMethodStatic = m.isStatic();
            currentReturnType = isCtor ? "void" : m.returnType();
            // an Apex `abstract` method in a class is bodyless: emit `abstract <ret> name(args);`
            // (Java requires no body and rejects an empty `{ }` for a non-void return).
            boolean isAbstractMethod = m.isAbstract() && !isCtor;
            sb.append(member).append("public ");
            if (isAbstractMethod) sb.append("abstract ");
            if (m.isStatic()) sb.append("static ");
            if (!isCtor) sb.append(mapType(m.returnType())).append(' ');
            sb.append(m.name()).append('(');
            for (int i = 0; i < m.params().size(); i++) {
                if (i > 0) sb.append(", ");
                Param p = m.params().get(i);
                sb.append(mapType(p.type())).append(' ').append(p.name());
            }
            if (isAbstractMethod) {
                sb.append(");\n");
                continue;
            }
            sb.append(") {\n");
            for (Stmt s : m.body()) emitStmt(s, stmt, sb);
            sb.append(member).append("}\n");
        }

        // nested classes, emitted as Java static nested classes
        java.util.Map<String, String> myStatics = new java.util.LinkedHashMap<>(outerStatics);
        for (Field f : cls.fields()) if (f.isStatic()) myStatics.put(f.name(), f.type());
        for (ClassDecl inner : cls.inners()) {
            sb.append('\n').append(member).append("static ");
            emitClassBody(inner, myStatics, member, sb);
        }

        sb.append(indent).append("}\n");
        this.fieldTypes = myFields; // restore this body's view for any trailing use
        this.staticFields = prevStaticFields;
        typer.currentClass = prevClass;
    }

    // --- statements
    private void emitStmt(Stmt s, String indent, StringBuilder sb) {
        // source map: the upcoming Java line maps back to this statement's Apex line
        Integer apexLine = stmtLines.get(s);
        if (apexLine != null) {
            int javaLine = countNewlines(sb) + 1;
            lineMap.putIfAbsent(javaLine, apexLine);
        }
        switch (s) {
            case VarDecl v -> {
                // The local isn't in Apex scope until AFTER its own declarator (Apex even rejects
                // `Integer x = x + 1`), so bind the name into `locals` only AFTER the initializer is
                // emitted. Binding it before let the case-insensitive locals lookup match a STATIC
                // call target that shares the type's name (`CachedItems x = CachedItems.fromJson(...)`,
                // where CachedItems degrades to SObject and falls to the typed else-branch), emitting
                // a self-referencing initializer. See the post-emit locals.put below.
                if (v.init() == null) {
                    // Apex locals default to null; emit it so Java's definite-assignment
                    // rule is satisfied too (everything maps to a boxed/reference type)
                    sb.append(indent).append(mapType(v.type())).append(' ')
                      .append(v.name()).append(" = null;\n");
                } else if (v.init() instanceof Null) {
                    sb.append(indent).append(mapType(v.type())).append(' ')
                      .append(v.name()).append(" = null;\n");
                } else if (v.init() instanceof ListLit || v.init() instanceof MapLit) {
                    // declared type, not var, so a later reassignment isn't pinned to
                    // the literal's (possibly anonymous) type
                    sb.append(indent).append(mapType(v.type())).append(' ').append(v.name())
                      .append(" = ").append(emitExpr(v.init())).append(";\n");
                } else if (v.init() instanceof Soql) {
                    // List<Account> a = [SELECT...] -> Account.many(query); declared type so
                    // the typed-vs-generic decision is the var's, not the query's
                    sb.append(indent).append(mapType(v.type())).append(' ').append(v.name())
                      .append(" = ").append(emitTypedInit(v.type(), v.init())).append(";\n");
                } else if (isTypedSObjectList(v.type()) && iterableIsSObjectList(v.init())) {
                    // List<OrderItem__c> lines = ord.OrderItems__r; — the init types List<SObject>
                    // (a child-relationship read / query helper), the declared slot is a typed list,
                    // so apply the same .many() covariance wrap the for-each/SOQL-return sites use.
                    sb.append(indent).append(mapType(v.type())).append(' ').append(v.name())
                      .append(" = ").append(emitListCovariant(v.type(), v.init())).append(";\n");
                } else if (v.init() instanceof Prop pr && isSObject(pr.target())
                        && !isTyped(sObjectTypeOf(pr.target()))) {
                    // untyped sObject field read: get() returns Object, cast to declared type
                    String t = mapType(v.type());
                    sb.append(indent).append(t).append(' ').append(v.name())
                      .append(" = (").append(t).append(") ").append(emitExpr(v.init())).append(";\n");
                } else if (v.init() instanceof Prop pr2 && isSObject(pr2.target())) {
                    // typed sObject field read: the getter is already typed, declared type
                    // (not var) so an Integer x = a.Name mismatch is a compile error
                    sb.append(indent).append(mapType(v.type())).append(' ').append(v.name())
                      .append(" = ").append(emitExpr(v.init())).append(";\n");
                } else if (isDecimalOrDoubleType(v.type()) && isIntegerExpr(v.init())) {
                    // Apex widens Integer to Decimal/Double; Java needs the explicit conversion
                    sb.append(indent).append(mapType(v.type())).append(' ').append(v.name())
                      .append(" = ").append(coerceNumeric(v.type(), v.init())).append(";\n");
                } else {
                    // Every Apex VarDecl carries a source-declared type (the parser never infers),
                    // so emit the MAPPED declared type — never `var`. A declared type that can't be
                    // loaded degrades to the dynamic SObject (mapType); when it does, the initializer
                    // may emit as Object (a degraded static call, ConnectApi.unsupported(...)) which
                    // javac won't narrow to SObject, so round-trip it through Object — preserving the
                    // pre-`var` "it compiles" guarantee. A non-degraded declared type keeps the plain
                    // assignment (the RHS is assignment-compatible in valid Apex; full type checking).
                    String lt = mapType(v.type());
                    String init = lt.equals("SObject")
                        ? "(SObject)(Object) " + emitExpr(v.init())
                        : emitExpr(v.init());
                    sb.append(indent).append(lt).append(' ').append(v.name())
                      .append(" = ").append(init).append(";\n");
                }
                // bind AFTER the initializer is emitted (see the comment at the top of this case)
                locals.put(v.name(), qualifyInnerType(v.type()));
            }
            case Assign a -> {
                if (a.target() instanceof Index ix) {
                    sb.append(indent).append(emitExpr(ix.target())).append(".set(")
                      .append(emitExpr(ix.index())).append(", ")
                      .append(emitExpr(a.value())).append(");\n");
                } else if (a.target() instanceof Prop pr && isSObject(pr.target())) {
                    String parent = sObjectTypeOf(pr.target());
                    if (isTyped(parent)) {
                        // typed: a.Name = x -> a.setName(x) (javac checks the value's type)
                        sb.append(indent).append(emitExpr(pr.target())).append(".set")
                          .append(schema.canonicalField(parent, pr.name())).append('(')
                          .append(coerceNumeric(schema.fieldType(parent, pr.name()), a.value())).append(");\n");
                    } else {
                        sb.append(indent).append(emitExpr(pr.target())).append(".put(\"")
                          .append(pr.name()).append("\", ").append(emitExpr(a.value())).append(");\n");
                    }
                } else if (a.value() instanceof Soql && a.target() instanceof Name sn
                        && locals.containsKey(sn.ident())) {
                    // x = [SELECT...] -> re-type the query result to x's declared type
                    sb.append(indent).append(sn.ident()).append(" = ")
                      .append(emitTypedInit(locals.get(sn.ident()), a.value())).append(";\n");
                } else if (a.target() instanceof Name cn && locals.containsKey(cn.ident())
                        && isTypedSObjectList(locals.get(cn.ident())) && iterableIsSObjectList(a.value())) {
                    // lines = ord.OrderItems__r; — the value types List<SObject>, the target is a
                    // typed-sObject-list local, so re-type via the same .many() covariance wrap.
                    sb.append(indent).append(cn.ident()).append(" = ")
                      .append(emitListCovariant(locals.get(cn.ident()), a.value())).append(";\n");
                } else if (a.target() instanceof Name nm && locals.containsKey(nm.ident())
                        && a.value() instanceof Prop vp && isSObject(vp.target())
                        && !isTyped(sObjectTypeOf(vp.target()))) {
                    // typed var = sObject field -> cast Object back to the var's declared type
                    String t = mapType(locals.get(nm.ident()));
                    sb.append(indent).append(nm.ident()).append(" = (").append(t).append(") ")
                      .append(emitExpr(a.value())).append(";\n");
                } else if (a.target() instanceof Name dnm && locals.containsKey(dnm.ident())
                        && isDecimalOrDoubleType(locals.get(dnm.ident())) && isIntegerExpr(a.value())) {
                    // Apex widens Integer to Decimal/Double on assignment; Java needs it explicit
                    sb.append(indent).append(dnm.ident()).append(" = ")
                      .append(coerceNumeric(locals.get(dnm.ident()), a.value())).append(";\n");
                } else if (a.target() instanceof Prop pp && propFieldType(pp) != null
                        && isIntegerExpr(a.value())
                        && isDecimalOrDoubleType(propFieldType(pp))) {
                    // cross-class user field of Decimal/Double type (cart.cost = 333): widen the
                    // Integer, now that the member-type index makes the field's declared type resolvable
                    sb.append(indent).append(emitExpr(a.target())).append(" = ")
                      .append(coerceNumeric(propFieldType(pp), a.value())).append(";\n");
                } else {
                    sb.append(indent).append(emitExpr(a.target())).append(" = ")
                      .append(emitExpr(a.value())).append(";\n");
                }
            }
            case ExprStmt e -> {
                // a safe-nav call as a STATEMENT may be void (obj?.doWork();) — a Function lambda
                // can't have a void body, so lower it via Safe.run (Consumer) instead of Safe.nav.
                String expr = e.expr() instanceof MethodCall mc && mc.safe()
                    ? safeRunCall(mc) : emitExpr(e.expr());
                sb.append(indent).append(expr).append(";\n");
            }
            case Return r -> {
                sb.append(indent).append("return");
                if (r.value() instanceof Soql) {
                    // return [SELECT...] -> re-type the query result to the return type
                    sb.append(' ').append(emitTypedInit(currentReturnType, r.value()));
                } else if (r.value() instanceof Prop p && isSObject(p.target())
                        && !isTyped(sObjectTypeOf(p.target()))
                        && !mapType(currentReturnType).equals("Object")) {
                    // untyped return of an sObject field -> cast Object back to the return type
                    sb.append(" (").append(mapType(currentReturnType)).append(") ").append(emitExpr(r.value()));
                } else if (r.value() != null) {
                    sb.append(' ').append(coerceNumeric(currentReturnType, r.value()));
                }
                sb.append(";\n");
            }
            case If iff -> {
                sb.append(indent).append("if (").append(emitExpr(iff.cond())).append(") {\n");
                scopedBlock(iff.thenBody(), indent + "    ", sb); // then-branch is its own scope
                sb.append(indent).append("}");
                if (!iff.elseBody().isEmpty()) {
                    sb.append(" else {\n");
                    scopedBlock(iff.elseBody(), indent + "    ", sb); // else-branch is its own scope
                    sb.append(indent).append("}");
                }
                sb.append('\n');
            }
            case While w -> {
                sb.append(indent).append("while (").append(emitExpr(w.cond())).append(") {\n");
                scopedBlock(w.body(), indent + "    ", sb);
                sb.append(indent).append("}\n");
            }
            case ForEach fe -> {
                // the loop var is scoped to the loop: snapshot BEFORE binding it, restore after the
                // body, so a `for (Account a : ...)` (or a type-shadowing var) doesn't leak past the
                // loop. The save/restore wraps the body emission below (the loop var enters `locals`).
                java.util.Map<String, String> saved = new java.util.HashMap<>(locals);
                try {
                    locals.put(fe.name(), qualifyInnerType(fe.type()));
                    // for (Account a : <List<SObject>>) -> iterate the re-typed rows. The source is
                    // either a SOQL result or a child-relationship collection (ord.Items__r), both
                    // List<SObject>; .many() re-types each row to the typed loop var (Java is invariant,
                    // so a bare List<SObject> can't bind a typed-sObject loop var directly).
                    // canonical class name for the .many() re-type so a lowercase loop type still links
                    String iterable = isTyped(base(fe.type())) && iterableIsSObjectList(fe.iterable())
                        ? typedName(base(fe.type())) + ".many(" + emitExpr(fe.iterable()) + ")"
                        : emitExpr(fe.iterable());
                    sb.append(indent).append("for (").append(mapType(fe.type())).append(' ')
                      .append(fe.name()).append(" : ").append(iterable).append(") {\n");
                    for (Stmt st : fe.body()) emitStmt(st, indent + "    ", sb);
                    sb.append(indent).append("}\n");
                } finally {
                    locals.clear();
                    locals.putAll(saved);
                }
            }
            case For f -> {
                // the init declaration is scoped to the loop (Apex): snapshot before the clause and
                // body, restore after, so an init var and any body-local don't leak past the loop.
                java.util.Map<String, String> saved = new java.util.HashMap<>(locals);
                try {
                    if (f.init() instanceof VarDecl iv) locals.put(iv.name(), qualifyInnerType(iv.type()));
                    // multi-declarator init (for (Integer i = 0, len = n; ...)): block-scope EVERY name
                    else if (f.init() instanceof Group g) {
                        for (Stmt st : g.stmts()) {
                            if (st instanceof VarDecl gv) locals.put(gv.name(), qualifyInnerType(gv.type()));
                        }
                    }
                    sb.append(indent).append("for (")
                      .append(f.init() == null ? "" : emitForClause(f.init())).append("; ")
                      .append(f.cond() == null ? "" : emitExpr(f.cond())).append("; ")
                      .append(f.update() == null ? "" : emitForClause(f.update())).append(") {\n");
                    for (Stmt st : f.body()) emitStmt(st, indent + "    ", sb);
                    sb.append(indent).append("}\n");
                } finally {
                    locals.clear();
                    locals.putAll(saved);
                }
            }
            case Dml d -> sb.append(indent).append("Database.").append(d.op())
                .append('(').append(emitExpr(d.value())).append(");\n");
            case Throw th -> sb.append(indent).append("throw ")
                .append(emitExpr(th.value())).append(";\n");
            case Group grp -> {
                // multi-declaration: emit each at the same indent, no new scope
                for (Stmt st : grp.stmts()) emitStmt(st, indent, sb);
            }
            case GuardedBlock g -> {
                // guard (e.g. System.runAs(u)) has no local equivalent — run the body in a plain block
                sb.append(indent).append("{\n");
                scopedBlock(g.body(), indent + "    ", sb);
                sb.append(indent).append("}\n");
            }
            case SwitchStmt sw -> emitSwitch(sw, indent, sb);
            case Try tr -> {
                sb.append(indent).append("try {\n");
                scopedBlock(tr.body(), indent + "    ", sb); // try body is its own scope
                sb.append(indent).append("}");
                Set<String> seenCatch = new java.util.HashSet<>();
                for (Catch c : tr.catches()) {
                    String ct = mapType(c.type());
                    if (!seenCatch.add(ct)) continue; // dedupe catches collapsing to one Java type
                    sb.append(" catch (").append(ct).append(' ').append(c.name()).append(") {\n");
                    // the catch param is in scope only inside its block: bind it, then restore.
                    java.util.Map<String, String> saved = new java.util.HashMap<>(locals);
                    try {
                        locals.put(c.name(), qualifyInnerType(c.type()));
                        for (Stmt st : c.body()) emitStmt(st, indent + "    ", sb);
                    } finally {
                        locals.clear();
                        locals.putAll(saved);
                    }
                    sb.append(indent).append("}");
                }
                if (!tr.finallyBody().isEmpty()) {
                    sb.append(" finally {\n");
                    scopedBlock(tr.finallyBody(), indent + "    ", sb); // finally body is its own scope
                    sb.append(indent).append("}");
                }
                sb.append('\n');
            }
        }
    }

    // Apex is BLOCK-scoped, but `locals` is one method-wide map that emission mutates as a side
    // effect (VarDecl, for-each loop var, classic-for init, catch param all `put` into it). Without
    // restoration a block-local name would LEAK past its block and the case-insensitive shadow guard
    // would then suppress a field token later in the method (where the name is out of scope). So run
    // each block body between a snapshot and a restore: the snapshot is the exact save/restore idiom
    // safeNav uses for its synthetic param, lifted to the whole map. Restoring the saved entries (not
    // bare removals) also handles SHADOWING — a block-local that overrode an outer field copied into
    // `locals` brings the field's value (and its instance semantics) back when the block ends. Only
    // method-wide entries (fields seeded at method start, params) survive, exactly as Apex requires.
    // The reassignment set (collectReassigned/methodScopeLocals) is a SEPARATE method-wide walk and
    // is deliberately NOT scoped here (Java's effectively-final rule is method-wide, not block-wide).
    private void scopedBlock(List<Stmt> body, String indent, StringBuilder sb) {
        java.util.Map<String, String> saved = new java.util.HashMap<>(locals);
        try {
            for (Stmt st : body) emitStmt(st, indent, sb);
        } finally {
            locals.clear();
            locals.putAll(saved);
        }
    }

    // Apex `switch on s { when a,b {..} when else {..} }` -> a null-safe if/else-if chain on a temp.
    // Java's own `switch` is avoided on purpose: it can't match `null` (Apex `when null` is legal) and
    // its String/enum value semantics differ. The subject is evaluated ONCE into a `var` temp, each
    // arm tests it with java.util.Objects.equals (null-safe, so a null subject / `when null` both
    // work), and the `when else` arm becomes the trailing `else`. Each body is its own block scope.
    private void emitSwitch(SwitchStmt sw, String indent, StringBuilder sb) {
        String temp = "__sw" + switchId++;
        // bind the temp's type so the arm bodies type-check against it the same as any local
        String subjectType = typer.typeOf(sw.subject());
        java.util.Map<String, String> saved = new java.util.HashMap<>(locals);
        try {
            locals.put(temp, subjectType);
            sb.append(indent).append("{\n");
            String inner = indent + "    ";
            sb.append(inner).append("var ").append(temp).append(" = ")
              .append(emitExpr(sw.subject())).append(";\n");
            boolean first = true;
            for (WhenCase c : sw.cases()) {
                sb.append(inner);
                sb.append(first ? "if (" : "else if (");
                first = false;
                java.util.List<String> tests = new java.util.ArrayList<>();
                for (Expr v : c.values()) {
                    tests.add("java.util.Objects.equals(" + temp + ", " + emitExpr(v) + ")");
                }
                sb.append(String.join(" || ", tests)).append(") {\n");
                scopedBlock(c.body(), inner + "    ", sb);
                sb.append(inner).append("}\n");
            }
            if (!sw.elseBody().isEmpty()) {
                // a `when else` with no preceding `when` arm becomes a bare block (no `if` to chain).
                sb.append(inner).append(first ? "{\n" : "else {\n");
                scopedBlock(sw.elseBody(), inner + "    ", sb);
                sb.append(inner).append("}\n");
            }
            sb.append(indent).append("}\n");
        } finally {
            locals.clear();
            locals.putAll(saved);
        }
    }

    // a classic-for init/update clause rendered inline (no indent, no ';')
    private String emitForClause(Stmt s) {
        return switch (s) {
            case VarDecl v -> {
                if (v.init() == null) yield mapType(v.type()) + " " + v.name();
                if (v.init() instanceof Null) yield mapType(v.type()) + " " + v.name() + " = null";
                yield "var " + v.name() + " = " + emitExpr(v.init());
            }
            case Assign a -> emitExpr(a.target()) + " = " + emitExpr(a.value());
            case ExprStmt e -> emitExpr(e.expr());
            // multi-declarator for-init: for (Integer i = 0, len = items.size(); ...). Java permits
            // a single compound declaration only with an EXPLICIT type (not `var`), so emit the
            // mapped boxed type once followed by `name [= init]` for each declarator.
            case Group g -> {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < g.stmts().size(); k++) {
                    VarDecl v = (VarDecl) g.stmts().get(k);
                    if (k == 0) sb.append(mapType(v.type())).append(' ');
                    else sb.append(", ");
                    sb.append(v.name());
                    if (v.init() != null) sb.append(" = ").append(emitExpr(v.init()));
                }
                yield sb.toString();
            }
            default -> throw new IllegalStateException("unsupported for-clause: " + s);
        };
    }

    // --- expressions
    private String emitExpr(Expr e) {
        return switch (e) {
            case Num n -> String.valueOf(n.value());
            case DecimalLit d -> "Decimal.valueOf(\"" + d.value() + "\")";
            case Str s -> '"' + escape(s.value()) + '"';
            case Bool b -> String.valueOf(b.value());
            case Null ignored -> "null";
            case Name n -> canonicalName(n.ident());
            // ++/-- must stay bare: "(++i);" is not a valid Java statement
            case Unary u when u.op().equals("++") || u.op().equals("--") ->
                u.op() + emitExpr(u.operand());
            // Apex unary -/+ on a Decimal: Java has no operator for the Decimal class,
            // so -x -> x.negate() and +x -> x (a no-op)
            case Unary u when (u.op().equals("-") || u.op().equals("+")) && isDecimal(u.operand()) ->
                u.op().equals("-") ? "(" + emitExpr(u.operand()) + ").negate()" : emitExpr(u.operand());
            case Unary u -> "(" + u.op() + emitExpr(u.operand()) + ")";
            case Postfix p -> emitExpr(p.operand()) + p.op();
            case Binary b -> emitBinary(b);
            case Ternary t -> emitTernary(t);
            case Call c -> mapCallee(c.callee()) + "(" + emitCallArgs(c.callee(), c.args()) + ")";
            case New nw -> emitNew(nw);
            case ArrayNew a -> "new " + mapType("List<" + a.elementType() + ">")
                + "(java.util.Arrays.asList(new " + mapType(a.elementType())
                + "[" + emitExpr(a.size()) + "]))";
            case SObjectLit so -> emitSObject(so);
            case Soql q -> emitSoql(q);
            case Index ix -> emitExpr(ix.target()) + ".get(" + emitExpr(ix.index()) + ")";
            case ListLit l -> l.elements().isEmpty()
                ? "new " + mapType(l.type()) + "()"
                : "new " + mapType(l.type()) + "(java.util.Arrays.asList("
                    + emitListElements(l.type(), l.elements()) + "))";
            case MapLit m -> emitMapLit(m);
            case Prop p -> emitProp(p);
            case MethodCall mc -> emitMethodCall(mc);
            case Cast c -> emitCast(c);
            case InstanceOf io -> "(" + emitExpr(io.expr()) + " instanceof " + mapType(io.type()) + ")";
            case ClassLit cl -> {
                String b = cl.type();
                // A typed-sObject `.class` token carries the row type so the runtime JSON.deserialize
                // can materialize typed rows: `List<Item__c>.class` / `Item__c.class` both emit the
                // generated row class `Item__c.class` (Java has no `List<X>.class`, and the element
                // type is the useful signal). Every OTHER `.class` in Apex is a System.Type literal,
                // NOT a java.lang.Class, so emit the runtime Type — it composes where a Type is
                // expected (`new List<Type>{ Integer.class }`, `Test.setMock(Mock.class, ...)`),
                // while runtime sinks that branch on `instanceof Class` (JSON.deserialize) treat a
                // non-sObject token identically either way. The denoted type name is the identity.
                String elem = base(firstGeneric(b));
                yield isTyped(elem)
                    ? typedName(elem) + ".class"
                    : "Type.forName(\"" + b + "\")";
            }
        };
    }

    // Apex numeric-narrowing casts on a Decimal -> the runtime Decimal (a BigDecimal) can't be
    // Java-cast to a primitive box, so route to the right extraction method. Only when the source
    // expr actually types as Decimal; otherwise the regular cast path applies (no behavior change).
    private static final java.util.Map<String, String> DECIMAL_NARROW = java.util.Map.of(
        "Integer", "intValue", "Long", "longValue", "Double", "doubleValue");

    // Integer.valueOf(dec)/Long.valueOf(dec): the canonical built-in type name -> the runtime
    // Decimal extraction it narrows to. Same intent as DECIMAL_NARROW, but for the static
    // valueOf form Apex allows on a Decimal (Java's java.lang.Integer/Long lack that overload).
    private static final java.util.Map<String, String> NUMERIC_VALUEOF_NARROW = java.util.Map.of(
        "Integer", "intValue", "Long", "longValue");

    private String emitCast(Cast c) {
        // (Integer) someDecimal / (Long) / (Double): emit dec.intValue()/longValue()/doubleValue()
        // instead of an illegal Java cast of a BigDecimal to a primitive box.
        String narrow = DECIMAL_NARROW.get(mapType(c.type()));
        if (narrow != null && isDecimal(c.expr())) {
            return "(" + emitExpr(c.expr()) + ")." + narrow + "()";
        }
        // (List<Account>) src: re-type and wrap each row into a real List<Account> (fixes both
        // javac's invariance and the runtime element type, exactly like a direct SOQL bound to
        // List<Account>). The .many() wrapper takes a List<SObject>, so the SOURCE's static type
        // decides how its argument is shaped:
        //   - already List<SObject> (a SOQL result, a child-relationship read): pass it straight.
        //   - anything else, typically Object (JSON.deserialize returns Object): down-cast to
        //     List<SObject> via Object first, so many() accepts it. The runtime JSON.deserialize,
        //     given the typed `.class` token, has already materialized the rows as generic SObjects.
        if (base(c.type()).equals("List")) {
            String elem = base(firstGeneric(c.type()));
            if (isTyped(elem)) {
                String src = iterableIsSObjectList(c.expr())
                    ? emitExpr(c.expr())
                    : "(List<SObject>)(Object) " + emitExpr(c.expr());
                return typedName(elem) + ".many(" + src + ")"; // canonical class name
            }
        }
        // (Account) src cast to a single typed sObject where the SOURCE doesn't already type as an
        // sObject — typically Object (a single-object JSON.deserialize, materialized into a generic
        // SObject). Wrap it as the typed row: a plain `(Account)(Object) src` would CCE on a generic
        // SObject at runtime. A List<SObject>-typed source takes one() (the first row). A source that
        // ALREADY types as an sObject keeps the plain cast below (unchanged behavior).
        if (isTyped(base(c.type())) && !isSObject(c.expr())) {
            String typed = typedName(base(c.type()));
            return iterableIsSObjectList(c.expr())
                ? typed + ".one(" + emitExpr(c.expr()) + ")"
                : "new " + typed + "((SObject)(Object) " + emitExpr(c.expr()) + ")";
        }
        String tt = mapType(c.type());
        // Java generics are invariant — a cast between parameterized collections
        // (List<SObject> vs List<Account>) must go via Object, mirroring Apex's
        // permissive collection downcast
        return tt.contains("<")
            ? "((" + tt + ")(Object) " + emitExpr(c.expr()) + ")"
            : "((" + tt + ") " + emitExpr(c.expr()) + ")";
    }

    // Apex lets a platform static be qualified with the System namespace: `System.Test.startTest()`,
    // `System.JSON.serialize(x)`, `System.Label.X`, `System.Type.forName(...)`. mapType already
    // strips `System.` for TYPE positions; this does the same for an EXPRESSION chain so it roots at
    // the platform TYPE (`Test`, `JSON`, `Label`, ...) instead of reaching javac as a field read
    // `System.Test`. The strip fires ONLY when the segment after `System.` NAMES a known platform
    // type (case-insensitive, as Apex is) — so `System.debug(...)`/`System.assert*`/`System.runAs`
    // (System METHODS, not type names) and `System.now()`/`System.today()` are left untouched and
    // stay direct System members. CHOICE: the strip re-roots to the bare platform name and lets the
    // normal routing resolve it, so a user class named exactly like a platform type (e.g. `Test`)
    // still WINS even for the System-qualified form — same precedence as the un-qualified `Test.x`.
    // Apex's "explicit System-qualification prefers the platform type" nuance doesn't fall out of a
    // pure AST re-root (the qualifier signal is gone after stripping); preferring the user type
    // keeps one consistent precedence rule, and a class colliding with a platform type name is rare.
    private static Expr stripSystemNamespace(Expr e) {
        if (e instanceof Prop p && p.target() instanceof Name root
                && root.ident().equalsIgnoreCase("System")
                && PLATFORM_CLASSES.containsKey(p.name().toLowerCase(java.util.Locale.ROOT))) {
            // `System.<X>` -> `<X>` (canonical platform casing), preserving the member name.
            return new Name(PLATFORM_CLASSES.get(p.name().toLowerCase(java.util.Locale.ROOT))
                .getSimpleName());
        }
        if (e instanceof Prop p) {
            Expr t = stripSystemNamespace(p.target());
            return t == p.target() ? p : new Prop(t, p.name(), p.safe());
        }
        if (e instanceof MethodCall mc) {
            Expr t = stripSystemNamespace(mc.target());
            return t == mc.target() ? mc : new MethodCall(t, mc.name(), mc.args(), mc.safe());
        }
        return e;
    }

    private String emitProp(Prop p) {
        // strip a `System.<PlatformType>` qualifier so the chain roots at the platform type itself
        Expr stripped = stripSystemNamespace(p);
        if (stripped != p && stripped instanceof Prop sp) {
            return emitProp(sp);
        }
        // `System.Label.<Name>` / `Label.<Name>` — the label text is org metadata (unavailable
        // locally), so the read degrades to the developer name via the runtime Label (see Label).
        if (p.target() instanceof Name lt && lt.ident().equalsIgnoreCase("Label")) {
            return "Label.get(\"" + escape(p.name()) + "\")";
        }
        // STATIC SObjectType token on a typed sObject TYPE: `Account.SObjectType` (the property
        // form) converges with `Account.getSObjectType()` (the call form, see methodCall) onto the
        // same runtime SObjectType. Same guards: a bare, UNSHADOWED sObject type name (a variable of
        // the same name keeps instance/field semantics; a user class isn't an sObject). The target
        // must be a genuine TYPE name, never `this`/the synthetic `<Cls>.this`: a class may declare a
        // FIELD called `sObjectType`, and `this.sObjectType` is an instance member read/WRITE, not the
        // token (isSObjectName's unknown-name fallback would otherwise treat `this` as an sObject). An
        // assignment TARGET never reaches here either — the Assign path emits its LHS field write
        // before deferring to expression emission (see emitStmt), mirroring the field-token guard.
        if (p.target() instanceof Name stn && !ExprTyper.isThisRef(stn.ident())
                && p.name().equalsIgnoreCase("SObjectType")
                && !localsHasIgnoreCase(stn.ident()) && !userClasses.contains(stn.ident())
                && isSObjectName(stn.ident())) {
            return "new SObjectType(\"" + escape(typedName(stn.ident())) + "\")";
        }
        // native Apex enum member access is case-insensitive (LoggingLevel.Info); the runtime
        // enum constants are canonical UPPER_CASE, so fold the member to match
        if (p.target() instanceof Name tn) {
            String runtimeEnum = runtimeEnum(tn.ident());
            if (runtimeEnum != null) {
                return runtimeEnum + "." + p.name().toUpperCase(java.util.Locale.ROOT);
            }
        }
        // ApexPages.Severity.<member>: nested native enum, case-insensitive like the rest
        if (p.target() instanceof Prop tp && tp.target() instanceof Name tpn
                && tpn.ident().equalsIgnoreCase("ApexPages") && tp.name().equalsIgnoreCase("Severity")) {
            return "ApexPages.Severity." + p.name().toUpperCase(java.util.Locale.ROOT);
        }
        // Schema.SObjectType.<Name>... static describe chain — org-coupled, degrade to Object
        if (namespaceRoot(p, "Schema") && startsWithMember(p, "SObjectType")) {
            return "Schema.describeToken(\"" + escape(dottedTail(p, 2)) + "\")";
        }
        // any ConnectApi.* access (a nested type/static field) degrades to Object
        if (namespaceRoot(p, "ConnectApi")) {
            return "ConnectApi.unsupported(\"" + escape(dottedTail(p, 1)) + "\")";
        }
        if (p.safe()) { // Apex a?.b -> Safe.nav(a, x -> x.b): a evaluated once, result type inferred
            // A Prop's member is the access; only the target can carry references, and that's the
            // safe-nav target itself (always emitted once, never captured) -> no reassignment risk
            // beyond what the chain root contributes, so no extra refs to inspect here.
            return safeNav(p.target(), java.util.Set.of(), new Prop(p.target(), p.name()),
                arg -> fieldAccess(new Prop(arg, p.name())));
        }
        return fieldAccess(p);
    }

    // Lower an Apex safe-navigation hop (a?.b / a?.m()) to Safe.nav(<emit a>, __sn -> <access>).
    // The target is emitted ONCE as the helper's first argument; the access is rebuilt to hang off
    // a synthetic lambda parameter (a bare Name bound to the target's static type in `locals`, so
    // typed-getter routing and param coercion still resolve) instead of re-emitting the target.
    // Single evaluation (no double side effects, no exponential blowup on deep chains), and the
    // result type is inferred from the lambda body so javac never synthesizes a <nulltype> ternary.
    //
    // `accessRefs` are the bare names READ by the access (call args), and `accessForType` is a
    // non-safe clone of the access used only to type the result. If any referenced name is in the
    // method's reassigned set, the Safe.nav lambda can't capture it (javac: "must be effectively
    // final"), so we fall back to a ternary. The fallback re-emits the target (sacrificing single
    // evaluation ON THIS PATH ONLY) but keeps a TYPED null branch when the type is known, so the
    // <nulltype>-poisoning the ternary historically caused is still avoided.
    private String safeNav(Expr target, Set<String> accessRefs, Expr accessForType,
                           java.util.function.Function<Name, String> access) {
        return safeNav("nav", target, accessRefs, accessForType, access);
    }

    // As above, but `helper` selects the runtime entry point: "nav" (Function -> value) for value
    // position, "run" (Consumer -> no value) for a statement-position call that may be void.
    private String safeNav(String helper, Expr target, Set<String> accessRefs, Expr accessForType,
                           java.util.function.Function<Name, String> access) {
        String targetType = typer.typeOf(target); // may be null (unknown) -> dynamic access path
        boolean captureUnsafe = false;
        for (String ref : accessRefs) {
            if (reassignedLocals.contains(ref)) { captureUnsafe = true; break; }
        }
        if (captureUnsafe) {
            return safeNavTernary(helper, target, targetType, accessForType, access);
        }
        String param = "__sn" + safeNavId++;
        Name arg = new Name(param);
        String emittedTarget = emitExpr(target);  // emit BEFORE binding param (no self-reference)
        String prev = locals.put(param, targetType);
        try {
            return "Safe." + helper + "(" + emittedTarget + ", " + param + " -> " + access.apply(arg) + ")";
        } finally {
            if (prev == null) {
                locals.remove(param);
            } else {
                locals.put(param, prev);
            }
        }
    }

    // Fallback lowering for a VALUE-position safe-nav whose access captures a reassigned local
    // (lambda capture is illegal in Java). Re-emit the target in BOTH the null test and the access —
    // this is the one place the single-evaluation property is intentionally given up (the access
    // references a reassigned name anyway, so the target tends to be a simple local/param, not a
    // side-effecting call). The null branch carries the mapped Java type as a cast when the result
    // type is known, so the expression's static type stays the access's type instead of <nulltype>;
    // an unknown type degrades to a bare `null` (the historical shape, only when nothing better).
    private String safeNavTernary(String helper, Expr target, String targetType,
                                  Expr accessForType, java.util.function.Function<Name, String> access) {
        String emittedTarget = emitExpr(target);
        String body = safeNavReemit(target, targetType, emittedTarget, access);
        String resultType = typer.typeOf(accessForType);
        String nullBranch = resultType == null ? "null" : "(" + mapType(resultType) + ") null";
        return "((" + emittedTarget + ") == null ? " + nullBranch + " : " + body + ")";
    }

    // The leftmost identifier of a Prop/MethodCall chain (a.b.c -> "a"), or null if it
    // doesn't bottom out at a bare Name. Lets a whole namespace chain be recognized by root.
    private static String chainRoot(Expr e) {
        while (true) {
            if (e instanceof Prop p) e = p.target();
            else if (e instanceof MethodCall mc) e = mc.target();
            else break;
        }
        return e instanceof Name n ? n.ident() : null;
    }

    /** True when a Prop chain is rooted at the named namespace (e.g. ConnectApi.X.y). */
    private static boolean namespaceRoot(Prop p, String namespace) {
        return namespace.equalsIgnoreCase(chainRoot(p));
    }

    /** True when the immediate child of the namespace root is the given member (Schema.SObjectType...). */
    private static boolean startsWithMember(Prop p, String member) {
        Expr e = p;
        while (e instanceof Prop pp && pp.target() instanceof Prop) e = ((Prop) e).target();
        return e instanceof Prop pr && pr.name().equalsIgnoreCase(member);
    }

    // The dotted path of a Prop chain past `dropLeading` leading segments, for a diagnostic
    // string (ConnectApi.ChatterFeeds.X -> "ChatterFeeds.X" with dropLeading=1).
    private static String dottedTail(Expr e, int dropLeading) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        while (e instanceof Prop p) {
            parts.addFirst(p.name());
            e = p.target();
        }
        if (e instanceof Name n) parts.addFirst(n.ident());
        List<String> all = new ArrayList<>(parts);
        return String.join(".", all.subList(Math.min(dropLeading, all.size()), all.size()));
    }

    private String fieldAccess(Prop p) {
        // Apex trigger context: Trigger.new (a Java keyword) and friends map onto the
        // runtime Trigger stub's static fields (case-insensitive, like Apex).
        if (p.target() instanceof Name tn && tn.ident().equalsIgnoreCase("Trigger")) {
            String m = p.name().toLowerCase(java.util.Locale.ROOT);
            return "Trigger." + TRIGGER_MEMBERS.getOrDefault(m, p.name());
        }
        // sObject field TOKEN: `Item__c.Id` where Item__c is a TYPED sObject TYPE name (not an
        // instance, not shadowed by a local/field) -> the static SObjectField token the generated
        // class now carries. Fold the field to its canonical API name (Item__c.id -> Item__c.Id),
        // exactly as instance field access folds, so it binds to the emitted `public static final`.
        String token = staticFieldToken(p);
        if (token != null) {
            return token;
        }
        String parent = sObjectTypeOf(p.target());
        if (parent == null) {
            // not an sObject — resolve the member case-insensitively against the target's
            // declared class (Apex: incomingItem.Name == incomingItem.name)
            return emitExpr(p.target()) + "." + canonicalMember(declaredTypeOf(p.target()), p.name());
        }
        if (isTyped(parent)) {
            // a child-relationship collection (ord.OrderItems__r): NOT a described field, so there's
            // no typed getter — route to the dynamic runtime accessor, which returns the List<SObject>
            // a SOQL child subquery stored under the relationship name (typed List<SObject> by the typer).
            if (schema.fieldType(parent, p.name()) == null && typer.isChildRelationship(parent, p.name())) {
                return emitExpr(p.target()) + ".getSObjects(\"" + p.name() + "\")";
            }
            // typed sObject: a.Name -> a.getName() (javac checks the field exists + its type)
            return emitExpr(p.target()) + ".get" + schema.canonicalField(parent, p.name()) + "()";
        }
        String get = emitExpr(p.target()) + ".get(\"" + p.name() + "\")";
        String ft = schema.fieldType(parent, p.name());
        if (ft == null) {
            return get; // not described -> Object
        }
        // Coerce numeric describe types the same way the generated getters do, so a
        // currency field that SOQL returned as an Integer doesn't CCE on a (Decimal) cast.
        return switch (mapType(ft)) {
            case "Decimal" -> "SObject.asDecimal(" + get + ")";
            case "Integer" -> "SObject.asInteger(" + get + ")";
            case "Long" -> "SObject.asLong(" + get + ")";
            case "Double" -> "SObject.asDouble(" + get + ")";
            default -> "((" + mapType(ft) + ") " + get + ")";
        };
    }

    // `Obj__c.Field` as a STATIC field-token reference (Schema.SObjectField), or null when this
    // Prop isn't that shape. The target must be a bare TYPE name (a typed sObject we generated a
    // class for), NOT shadowed by a local/param/field — Apex variables win over types, so a variable
    // named Item__c keeps instance semantics. The shadow check is case-INSENSITIVE (Apex names are):
    // a field declared `account` shadows the `Account` type, so `account.Id` (any case) is an INSTANCE
    // read, never the static token. (locals carries the current body's own fields too — see emitMethod.)
    // The member must be a real described field; the token name is the canonical-cased API name so
    // `Item__c.id` binds to the emitted `public static final SObjectField Id`.
    private String staticFieldToken(Prop p) {
        if (!(p.target() instanceof Name tn) || localsHasIgnoreCase(tn.ident())) {
            return null;
        }
        // canonical casing: the field token lives on the generated `class Account`, so a `account.Id`
        // (lowercase type) must emit `Account.Id`, not `account.Id` (no such class).
        String type = typedSObjects.canonical(tn.ident());
        if (type == null || schema.fieldType(type, p.name()) == null) {
            return null;
        }
        return type + "." + schema.canonicalField(type, p.name());
    }

    /** The declared (Apex) type of a target we can read cheaply: a local/param, or this.field. */
    private String declaredTypeOf(Expr e) {
        if (e instanceof Name n && !ExprTyper.isThisRef(n.ident())) {
            String t = locals.get(n.ident());
            if (t != null) {
                return t;
            }
            // a bare known class name -> static member access (CCConstants.SOME_CONST)
            if (memberIndex.containsKey(n.ident())) {
                return n.ident();
            }
        }
        // this.field — or the synthetic <Cls>.this.field a typed-literal rewrite emits — reads a
        // current-class field, whose type lives in locals (fields are seeded there per body).
        if (e instanceof Prop pr && pr.target() instanceof Name tn && ExprTyper.isThisRef(tn.ident())) {
            return locals.get(pr.name());
        }
        return null;
    }

    /** Resolve a field name case-insensitively against a known class's fields, else unchanged. */
    private String canonicalMember(String declaredType, String member) {
        if (declaredType == null) {
            return member;
        }
        String b = base(declaredType);
        java.util.Map<String, String> members = memberIndex.get(b);
        if (members == null && b.contains(".")) {
            members = memberIndex.get(b.substring(b.lastIndexOf('.') + 1)); // Outer.Inner -> Inner
        }
        return members == null ? member : members.getOrDefault(member.toLowerCase(java.util.Locale.ROOT), member);
    }

    private String emitMethodCall(MethodCall mc) {
        // Apex a?.m() in VALUE position -> Safe.nav(a, x -> x.m()). A statement-position call whose
        // result is discarded (and may be void) is lowered separately via safeRunCall (a Function
        // lambda can't have a void body); emitStmt routes the ExprStmt case there.
        if (mc.safe()) {
            // type the result off a NON-safe clone carrying the real target, so typeOf resolves the
            // method's return type (the typed null branch needs it); refs come from the call args.
            return safeNav(mc.target(), safeNavCallRefs(mc),
                new MethodCall(mc.target(), mc.name(), mc.args(), false),
                arg -> methodCall(new MethodCall(arg, mc.name(), mc.args(), false)));
        }
        return methodCall(mc);
    }

    // Bare names read by a safe-nav call's ARGUMENTS (the target is the safe-nav target itself,
    // never captured): if any is a reassigned local, the Safe.nav lambda can't capture it.
    private Set<String> safeNavCallRefs(MethodCall mc) {
        Set<String> refs = new java.util.HashSet<>();
        for (Expr a : mc.args()) collectRefs(a, refs);
        return refs;
    }

    // Statement-position safe-nav call (obj?.doWork();): lower to Safe.run(obj, x -> x.call()).
    // A Consumer body accepts a void OR value-returning call (the value is discarded), so this is
    // the one shape the Function-based Safe.nav can't express. Single evaluation, like safeNav.
    // When a call arg references a reassigned local (lambda capture illegal), fall back to a guarded
    // `if (target != null) target.call();` statement — valid even when the call is void (a ternary
    // can't carry a void branch), at the cost of re-emitting the target on this path only.
    private String safeRunCall(MethodCall mc) {
        Set<String> refs = safeNavCallRefs(mc);
        boolean captureUnsafe = false;
        for (String ref : refs) {
            if (reassignedLocals.contains(ref)) { captureUnsafe = true; break; }
        }
        if (captureUnsafe) {
            String emittedTarget = emitExpr(mc.target());
            String targetType = typer.typeOf(mc.target());
            String body = safeNavReemit(mc.target(), targetType, emittedTarget,
                arg -> methodCall(new MethodCall(arg, mc.name(), mc.args(), false)));
            return "if ((" + emittedTarget + ") != null) " + body;
        }
        return safeNav("run", mc.target(), refs,
            new MethodCall(new Name(""), mc.name(), mc.args(), false),
            arg -> methodCall(new MethodCall(arg, mc.name(), mc.args(), false)));
    }

    // Re-emit a safe-nav access directly on the target (no synthetic lambda param). When the target
    // is a bare Name it already carries its type in `locals`, so the access closure resolves
    // typed-getter routing / param coercion exactly as the lambda path; otherwise we bind a
    // throwaway param to the target's type, emit, then substitute the parenthesized target text.
    private String safeNavReemit(Expr target, String targetType, String emittedTarget,
                                 java.util.function.Function<Name, String> access) {
        if (target instanceof Name n) {
            return access.apply(n);
        }
        String param = "__sn" + safeNavId++;
        String prev = locals.put(param, targetType);
        try {
            return access.apply(new Name(param)).replace(param, "(" + emittedTarget + ")");
        } finally {
            if (prev == null) locals.remove(param); else locals.put(param, prev);
        }
    }

    // --- reassigned-locals analysis (one pass over a method body before emitting it)

    // Collect every local/param NAME written after declaration in `body`. The parser desugars
    // compound assigns (+= etc.) and statement-position ++/-- into Assign(Name, ...), and the
    // classic for var's update is just an Assign inside For.update, so walking Assign targets that
    // are bare Names — plus prefix ++/-- (Unary) and postfix ++/-- (Postfix) operand Names found in
    // any expression — catches them all. Enhanced-for vars are effectively final unless the body
    // assigns them, which the same Assign walk over the body already covers. Conservative on
    // purpose: a name landing in this set forces the safe-nav ternary fallback, never miscompiles.
    private static void collectReassigned(List<Stmt> body, Set<String> out) {
        for (Stmt s : body) collectReassigned(s, out);
    }

    private static void collectReassigned(Stmt s, Set<String> out) {
        switch (s) {
            case Assign a -> {
                if (a.target() instanceof Name n) out.add(n.ident());
                collectReassigned(a.target(), out);
                collectReassigned(a.value(), out);
            }
            case VarDecl v -> { if (v.init() != null) collectReassigned(v.init(), out); }
            case ExprStmt e -> collectReassigned(e.expr(), out);
            case Return r -> { if (r.value() != null) collectReassigned(r.value(), out); }
            case If i -> { collectReassigned(i.cond(), out);
                collectReassigned(i.thenBody(), out); collectReassigned(i.elseBody(), out); }
            case While w -> { collectReassigned(w.cond(), out); collectReassigned(w.body(), out); }
            case ForEach fe -> { collectReassigned(fe.iterable(), out); collectReassigned(fe.body(), out); }
            case For f -> {
                if (f.init() != null) collectReassigned(f.init(), out);
                if (f.cond() != null) collectReassigned(f.cond(), out);
                if (f.update() != null) collectReassigned(f.update(), out);
                collectReassigned(f.body(), out);
            }
            case Dml d -> collectReassigned(d.value(), out);
            case Try t -> { collectReassigned(t.body(), out);
                for (Catch c : t.catches()) collectReassigned(c.body(), out);
                collectReassigned(t.finallyBody(), out); }
            case Throw t -> collectReassigned(t.value(), out);
            case GuardedBlock g -> collectReassigned(g.body(), out);
            case Group g -> collectReassigned(g.stmts(), out);
            case SwitchStmt sw -> {
                collectReassigned(sw.subject(), out);
                for (WhenCase c : sw.cases()) {
                    for (Expr v : c.values()) collectReassigned(v, out);
                    collectReassigned(c.body(), out);
                }
                collectReassigned(sw.elseBody(), out);
            }
        }
    }

    // Walk an expression for in-place writes (prefix/postfix ++/-- on a bare Name) and for nested
    // statements there are none — but expressions can hide writes, so descend through every child.
    private static void collectReassigned(Expr e, Set<String> out) {
        if (e == null) return;
        switch (e) {
            case Unary u -> {
                if ((u.op().equals("++") || u.op().equals("--")) && u.operand() instanceof Name n) {
                    out.add(n.ident());
                }
                collectReassigned(u.operand(), out);
            }
            case Postfix p -> {
                if (p.operand() instanceof Name n) out.add(n.ident());
                collectReassigned(p.operand(), out);
            }
            case Binary b -> { collectReassigned(b.left(), out); collectReassigned(b.right(), out); }
            case Ternary t -> { collectReassigned(t.cond(), out);
                collectReassigned(t.then(), out); collectReassigned(t.els(), out); }
            case Call c -> { for (Expr a : c.args()) collectReassigned(a, out); }
            case New n -> { for (Expr a : n.args()) collectReassigned(a, out); }
            case ArrayNew a -> collectReassigned(a.size(), out);
            case SObjectLit so -> { for (FieldInit fi : so.fields()) collectReassigned(fi.value(), out); }
            case Index ix -> { collectReassigned(ix.target(), out); collectReassigned(ix.index(), out); }
            case ListLit l -> { for (Expr a : l.elements()) collectReassigned(a, out); }
            case MapLit m -> { for (Expr a : m.keys()) collectReassigned(a, out);
                for (Expr a : m.values()) collectReassigned(a, out); }
            case Prop p -> collectReassigned(p.target(), out);
            case MethodCall mc -> { collectReassigned(mc.target(), out);
                for (Expr a : mc.args()) collectReassigned(a, out); }
            case Cast c -> collectReassigned(c.expr(), out);
            case InstanceOf io -> collectReassigned(io.expr(), out);
            case Soql sq -> { for (Bind bd : sq.binds()) collectReassigned(bd.value(), out); }
            default -> { /* leaf (Num/Str/Bool/Null/Name/DecimalLit/ClassLit): no nested write */ }
        }
    }

    // Collect every NAME a method body introduces into scope (declared locals + bound names): plain
    // VarDecls, enhanced-for/for-classic loop vars, and catch params. Plus the method's params (added
    // by the caller). This is the shadow set for the typed-literal arg-value rewrite: a bare name that
    // is BOTH a current-class field AND in this set is a param/local that wins, so it stays bare.
    private static void collectDeclaredLocals(List<Stmt> body, Set<String> out) {
        for (Stmt s : body) collectDeclaredLocals(s, out);
    }

    private static void collectDeclaredLocals(Stmt s, Set<String> out) {
        switch (s) {
            case VarDecl v -> out.add(v.name());
            case ForEach fe -> { out.add(fe.name()); collectDeclaredLocals(fe.body(), out); }
            case For f -> {
                if (f.init() != null) collectDeclaredLocals(f.init(), out);
                collectDeclaredLocals(f.body(), out);
            }
            case If i -> { collectDeclaredLocals(i.thenBody(), out); collectDeclaredLocals(i.elseBody(), out); }
            case While w -> collectDeclaredLocals(w.body(), out);
            case Try t -> {
                collectDeclaredLocals(t.body(), out);
                for (Catch c : t.catches()) { out.add(c.name()); collectDeclaredLocals(c.body(), out); }
                collectDeclaredLocals(t.finallyBody(), out);
            }
            case GuardedBlock g -> collectDeclaredLocals(g.body(), out);
            case Group g -> collectDeclaredLocals(g.stmts(), out);
            case SwitchStmt sw -> {
                for (WhenCase c : sw.cases()) collectDeclaredLocals(c.body(), out);
                collectDeclaredLocals(sw.elseBody(), out);
            }
            default -> { /* Assign/ExprStmt/Return/Dml/Throw declare no new name */ }
        }
    }

    // Names referenced (read) by a safe-nav access subtree. For a Prop the name is the parser's
    // member token (always safe); only the call args of a MethodCall can reference captured locals,
    // so we collect bare Name idents recursively from those arg expressions. Used to decide whether
    // any referenced ident is reassigned (forcing the ternary fallback over the Safe.nav lambda).
    private static void collectRefs(Expr e, Set<String> out) {
        if (e == null) return;
        switch (e) {
            case Name n -> out.add(n.ident());
            case Unary u -> collectRefs(u.operand(), out);
            case Postfix p -> collectRefs(p.operand(), out);
            case Binary b -> { collectRefs(b.left(), out); collectRefs(b.right(), out); }
            case Ternary t -> { collectRefs(t.cond(), out); collectRefs(t.then(), out); collectRefs(t.els(), out); }
            case Call c -> { for (Expr a : c.args()) collectRefs(a, out); }
            case New n -> { for (Expr a : n.args()) collectRefs(a, out); }
            case ArrayNew a -> collectRefs(a.size(), out);
            case SObjectLit so -> { for (FieldInit fi : so.fields()) collectRefs(fi.value(), out); }
            case Index ix -> { collectRefs(ix.target(), out); collectRefs(ix.index(), out); }
            case ListLit l -> { for (Expr a : l.elements()) collectRefs(a, out); }
            case MapLit m -> { for (Expr a : m.keys()) collectRefs(a, out);
                for (Expr a : m.values()) collectRefs(a, out); }
            case Prop p -> collectRefs(p.target(), out);
            case MethodCall mc -> { collectRefs(mc.target(), out); for (Expr a : mc.args()) collectRefs(a, out); }
            case Cast c -> collectRefs(c.expr(), out);
            case InstanceOf io -> collectRefs(io.expr(), out);
            case Soql sq -> { for (Bind bd : sq.binds()) collectRefs(bd.value(), out); }
            default -> { /* leaf literal: nothing referenced */ }
        }
    }

    private String methodCall(MethodCall mc) {
        // strip a `System.<PlatformType>` qualifier so the call roots at the platform type itself
        // (`System.Test.startTest()` -> `Test.startTest()`); then re-enter on the re-rooted call.
        Expr strippedCall = stripSystemNamespace(mc);
        if (strippedCall != mc && strippedCall instanceof MethodCall smc) {
            return methodCall(smc);
        }
        String name = mc.name();
        // STATIC getSObjectType() on a typed sObject TYPE: `Account.getSObjectType()` is called ON
        // THE TYPE, but the runtime SObject only has an INSTANCE getSObjectType() (a static can't
        // share the signature in the hierarchy). So rewrite the call site to the SObjectType token
        // directly. The target must be a bare, UNSHADOWED type name (Apex variables win over types,
        // so a local `inv` named like its type keeps the instance call), the method must be
        // getSObjectType (case-insensitive) with no args, and the name must denote an sObject type.
        if (mc.args().isEmpty() && name.equalsIgnoreCase("getSObjectType")
                && mc.target() instanceof Name tn && !localsHasIgnoreCase(tn.ident())
                && !userClasses.contains(tn.ident()) && isSObjectName(tn.ident())) {
            return "new SObjectType(\"" + escape(typedName(tn.ident())) + "\")";
        }
        // a call whose target is a NESTED ConnectApi/Schema chain (not the bare namespace) is
        // org-coupled and unmodeled: degrade to the runtime placeholder. A call directly on the
        // bare `ConnectApi`/`Schema` name (Schema.getGlobalDescribe()) is a real static and
        // falls through to the normal emission below (the runtime class provides it).
        if (mc.target() instanceof Prop tp) {
            if (namespaceRoot(tp, "ConnectApi")) {
                return "ConnectApi.unsupported(\"" + escape(dottedTail(tp, 1) + "." + name) + "\")";
            }
            if (namespaceRoot(tp, "Schema") && startsWithMember(tp, "SObjectType")) {
                return "Schema.describeToken(\"" + escape(dottedTail(tp, 2) + "." + name) + "\")";
            }
        }
        // built-in static call (Apex is case-insensitive): canonicalize the type name
        // and lower-case the method's first char so Date.ValueOf -> Date.valueOf, etc.
        // A user class (or a local/param/field) of the same name SHADOWS the built-in —
        // Apex prefers the workspace's own type/variable — so skip this routing and let the
        // default emission below resolve it to the user symbol (mirrors the locals-precedence
        // guard the static-call coercion uses). Matters for plausible user names like Assert.
        if (mc.target() instanceof Name n
                && !userClasses.contains(n.ident()) && !locals.containsKey(n.ident())) {
            String canon = BUILTINS.get(n.ident().toLowerCase());
            if (canon != null) {
                // Integer.valueOf(dec) / Long.valueOf(dec): Apex narrows a Decimal; Java's
                // java.lang.Integer.valueOf has no Decimal overload, so route to the runtime
                // Decimal's truncating extraction (mirrors the DECIMAL_NARROW cast path).
                String narrow = NUMERIC_VALUEOF_NARROW.get(canon);
                if (narrow != null && name.equalsIgnoreCase("valueOf")
                        && mc.args().size() == 1 && isDecimal(mc.args().get(0))) {
                    return "(" + emitExpr(mc.args().get(0)) + ")." + narrow + "()";
                }
                String type = canon.equals("String") ? "Strings" : canon; // String statics -> helper
                // case-fold the method to the runtime class's real name (Database.executebatch ->
                // executeBatch); assert is the keyword-clash special case (-> assertTrue).
                String method = canon.equals("System") && name.equalsIgnoreCase("assert")
                    ? "assertTrue" : foldStaticMethod(type, name);
                return type + "." + method + "(" + emitArgs(mc.args()) + ")";
            }
        }
        // Apex String instance methods Java lacks -> Strings.method(theString, args)
        if (APEX_STRING_METHODS.contains(name) && isString(mc.target())) {
            String args = emitArgs(mc.args());
            return "Strings." + name + "(" + emitExpr(mc.target())
                + (args.isEmpty() ? "" : ", " + args) + ")";
        }
        // a STATIC call on a recognized platform class that ISN'T a BUILTINS namespace root
        // (Test, ApexPages, Limits, ...): fold the method name to the runtime class's real casing
        // (Apex is case-insensitive, so `Test.isrunningtest()` must reach `Test.isRunningTest()`).
        // Only fires for a bare, unshadowed type name (Apex variables win over types).
        if (mc.target() instanceof Name pn && !userClasses.contains(pn.ident())
                && !locals.containsKey(pn.ident())
                && PLATFORM_CLASSES.containsKey(pn.ident().toLowerCase(java.util.Locale.ROOT))) {
            String type = PLATFORM_CLASSES.get(pn.ident().toLowerCase(java.util.Locale.ROOT))
                .getSimpleName();
            String folded = foldStaticMethod(type, name);
            if (!folded.equals(name) || !type.equals(pn.ident())) {
                return type + "." + folded + "(" + emitArgs(mc.args()) + ")";
            }
        }
        // a method on a known user-class: coerce an Integer arg into a Decimal param (Apex widens).
        // An INSTANCE call uses the target's static type; a STATIC call (Factory.make(...)) has a
        // bare type-name target that typeOf reports null for (by design), so use that name as the
        // lookup key — the member-type index stores static methods' params under the same key.
        // Ambiguous overloads stay poisoned (AMBIGUOUS -> no coercion) for either call shape.
        // A local/field sharing the type's exact name shadows it (Apex: variables win over types),
        // so only treat the target as a static-call class name when no local/field claims it.
        String klass = mc.target() instanceof Name tn && !locals.containsKey(tn.ident())
                && typer.isKnownTypeName(tn.ident())
            ? tn.ident() : typer.typeOf(mc.target());
        String args = emitMethodArgs(klass, name, mc.args());
        // Apex instance calls are case-insensitive (s.toUppercase(), list.deepClone()): when the
        // receiver's static type resolves to a runtime/JDK-backed class, fold the method name to that
        // class's canonical Java spelling — the same reflective folding the static path uses. The
        // receiver type comes from the typer (typeOf), NOT from `klass` above, which may instead hold
        // a STATIC-call class name. An unknown/dynamic receiver maps to no class, so the name is left
        // exactly as written (a user-class method's case-insensitivity is a separate concern).
        String emitted = foldReceiverInstanceMethod(typer.typeOf(mc.target()), name);
        return emitExpr(mc.target()) + "." + emitted + "(" + args + ")";
    }

    // The canonically-cased Java instance-method name for a call whose receiver has Apex type
    // `receiverApexType`, when that type maps to a runtime/JDK-backed class (String/Id, List/Set/Map,
    // Decimal, Date/Datetime/Time, Blob); else `name` unchanged. Null/unknown receiver -> unchanged.
    private static String foldReceiverInstanceMethod(String receiverApexType, String name) {
        if (receiverApexType == null) {
            return name;
        }
        Class<?> rc = INSTANCE_RECEIVER_CLASSES.get(base(receiverApexType).toLowerCase(java.util.Locale.ROOT));
        return rc == null ? name : foldInstanceMethod(rc, name);
    }

    // emitArgs, coercing each arg into the declared param type of `klass.method`.
    private String emitMethodArgs(String klass, String method, List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceArg(paramTypeOf(klass, method, i), args.get(i)));
        }
        return String.join(", ", out);
    }

    // The single per-argument coercion at a CALL site, given the callee's declared param type: the
    // List<SObject>->List<Typed> covariance wrap when the param is a typed-sObject list (Java is
    // invariant, so a child-relationship/query-helper List<SObject> arg can't bind directly), else
    // the Integer->Decimal widening. Mutually exclusive (a List arg is never a Decimal); an unknown
    // param type (null/AMBIGUOUS) takes neither and emits the arg unchanged — no behavior change.
    private String coerceArg(String paramType, Expr arg) {
        if (isTypedSObjectList(paramType) && iterableIsSObjectList(arg)) {
            return emitListCovariant(paramType, arg);
        }
        return coerceNumeric(paramType, arg);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // the canonical runtime enum whose name matches case-insensitively, or null
    private static String runtimeEnum(String ident) {
        for (String e : RUNTIME_ENUMS) {
            if (e.equalsIgnoreCase(ident)) return e;
        }
        return null;
    }

    // Apex identifiers are case-insensitive; Java's aren't. Resolve a referenced name to
    // the exact spelling of the matching declared field/param/local so a use that differs
    // only in case (username vs userName) still binds. Unknown names pass through.
    private String canonicalName(String ident) {
        if (ident.equals("this") || locals.containsKey(ident)) {
            return ident;
        }
        for (String declared : locals.keySet()) {
            if (declared.equalsIgnoreCase(ident)) {
                return declared;
            }
        }
        return ident;
    }

    // Whether a local/param/field shadows `ident`, case-INSENSITIVELY (Apex names aren't case
    // sensitive: a variable `account` shadows the `Account` type). `locals` is seeded with this
    // body's own fields (emitMethod) plus params/locals as they're declared, so this one check
    // covers every variable in scope. Used by the static field-token path so a typed-sObject value
    // referenced by a same-named (any-case) variable stays an INSTANCE read, never a Schema token.
    private boolean localsHasIgnoreCase(String ident) {
        if (locals.containsKey(ident)) {
            return true;
        }
        for (String declared : locals.keySet()) {
            if (declared.equalsIgnoreCase(ident)) {
                return true;
            }
        }
        return false;
    }

    private String emitMapLit(MapLit m) {
        if (m.keys().isEmpty()) {
            return "new " + mapType(m.type()) + "()";
        }
        // the declared VALUE type (Map<K,V> -> V): widen an Integer value literal into a Decimal/Double
        // value slot, exactly as the assignment/arg sites do (Apex widens; Java won't for a class/box).
        String valueType = firstGeneric(m.type());
        // double-brace init: works for any key/value type without a runtime factory
        StringBuilder sb = new StringBuilder("new ").append(mapType(m.type())).append("(){{");
        for (int i = 0; i < m.keys().size(); i++) {
            sb.append(" put(").append(emitExpr(m.keys().get(i))).append(", ")
              .append(coerceNumeric(valueType, m.values().get(i))).append(");");
        }
        return sb.append(" }}").toString();
    }

    private static final Set<String> ARITH = Set.of("+", "-", "*", "/");

    // Thin wrappers over the central typer (ExprTyper) — the ONE source of truth for static
    // expression typing. Behavior is identical to before where typing was already correct, and
    // the typer's null/unknown answer falls through to the same default emission as before.

    /** a String-typed operand (so '+' is concatenation, never Decimal arithmetic) */
    private boolean isString(Expr e) {
        return typer.isString(e);
    }

    private boolean isDecimal(Expr e) {
        return typer.isDecimal(e);
    }

    private String decimalOperand(Expr e) {
        return isDecimal(e) ? emitExpr(e) : "Decimal.valueOf(" + emitExpr(e) + ")";
    }

    // Apex widens Integer to Decimal OR Double implicitly; Java widens to neither boxed type on its
    // own. Where the expected type is Decimal/Double and the value is an Integer, wrap it so the
    // assignment/return/arg type-checks. Everywhere else the value is emitted unchanged (no change).
    private String coerceNumeric(String expectedApexType, Expr value) {
        return coerceNumeric(expectedApexType, value, value);
    }

    // The widen DECISION runs on `typed` (the original expr the typer can type), but the emitted
    // text comes from `emit` (possibly an enclosing-qualified rewrite of `typed` — see emitSObject).
    // When the two are identical this is the plain coerce; the split only matters inside a typed
    // sObject literal where the arg value was rewritten to escape the anon init block's `this`.
    private String coerceNumeric(String expectedApexType, Expr typed, Expr emit) {
        // a ternary in a Decimal/Double context (Decimal d = c ? 0 : 1): widen PER BRANCH rather than
        // wrap the whole conditional in valueOf(Object) — keeps each branch statically typed.
        // emitTernary already widens a mixed Decimal/Integer ternary on its own; this adds the
        // all-Integer case, which only needs widening because the surrounding type is Decimal/Double.
        if (typed instanceof Ternary t && emit instanceof Ternary et
                && (isDecimalType(expectedApexType) || isDoubleType(expectedApexType))) {
            return "(" + emitExpr(et.cond())
                + " ? " + coerceNumeric(expectedApexType, t.then(), et.then())
                + " : " + coerceNumeric(expectedApexType, t.els(), et.els()) + ")";
        }
        if (typer.needsDecimalWiden(expectedApexType, typed)) {
            return "Decimal.valueOf(" + emitExpr(emit) + ")";
        }
        // Integer -> Double: an unboxing+widening cast Java accepts (Integer -> int -> double),
        // boxed back via Double.valueOf so the value's type matches the Double slot.
        if (typer.needsDoubleWiden(expectedApexType, typed)) {
            return "Double.valueOf((double)(" + emitExpr(emit) + "))";
        }
        return emitExpr(emit);
    }

    private boolean isDoubleType(String apexType) {
        return apexType != null && base(apexType).equalsIgnoreCase("Double");
    }

    private boolean isDecimalOrDoubleType(String apexType) {
        return isDecimalType(apexType) || isDoubleType(apexType);
    }

    private boolean isDecimalType(String apexType) {
        return apexType != null && base(apexType).equalsIgnoreCase("Decimal");
    }

    private boolean isIntegerExpr(Expr e) {
        return typer.isInteger(e);
    }

    // Apex `cond ? a : b` -> Java conditional. When one branch is a Decimal and the other an
    // Integer, widen the Integer branch (Decimal.valueOf) so the Java conditional has a single
    // type (Java unifies the branches to a common type; Decimal vs int wouldn't, and the result
    // type would be lost). Per-branch widening keeps static typing, unlike wrapping the whole
    // conditional in Decimal.valueOf(Object).
    private String emitTernary(Ternary t) {
        Expr then = t.then();
        Expr els = t.els();
        String thenJava;
        String elsJava;
        if (isDecimal(then) && isIntegerExpr(els)) {
            thenJava = emitExpr(then);
            elsJava = "Decimal.valueOf(" + emitExpr(els) + ")";
        } else if (isDecimal(els) && isIntegerExpr(then)) {
            thenJava = "Decimal.valueOf(" + emitExpr(then) + ")";
            elsJava = emitExpr(els);
        } else {
            thenJava = emitExpr(then);
            elsJava = emitExpr(els);
        }
        return "(" + emitExpr(t.cond()) + " ? " + thenJava + " : " + elsJava + ")";
    }

    private static final Set<String> RELATIONAL = Set.of("<", ">", "<=", ">=");

    private String emitBinary(Binary b) {
        // Apex ?? (null-coalescing): a ?? b -> Safe.nvl(a, b). NOTE the semantic tradeoff —
        // real Apex evaluates b ONLY when a is null; Safe.nvl evaluates both eagerly (the lambda
        // alternative would reintroduce the effectively-final capture problem safe-nav solved).
        // Documented on Safe.nvl. Emitted BEFORE the Decimal-arith routing: ?? isn't arithmetic.
        if (b.op().equals("??")) {
            return "Safe.nvl(" + emitExpr(b.left()) + ", " + emitExpr(b.right()) + ")";
        }
        // Apex Decimal arithmetic -> method calls (BigDecimal has no +/-/*// operators).
        // Guard: if either side is a String, '+' is concatenation, not addition.
        if (ARITH.contains(b.op()) && !isString(b.left()) && !isString(b.right())
            && (isDecimal(b.left()) || isDecimal(b.right()))) {
            String left = decimalOperand(b.left());
            String right = decimalOperand(b.right());
            return switch (b.op()) {
                case "+" -> left + ".add(" + right + ")";
                case "-" -> left + ".subtract(" + right + ")";
                case "*" -> left + ".multiply(" + right + ")";
                // Apex's default division rounding is HALF_EVEN (matches the runtime Decimal's
                // documented DEFAULT_ROUNDING); scale stays 8.
                default -> left + ".divide(" + right + ", 8, java.math.RoundingMode.HALF_EVEN)";
            };
        }
        // Apex Decimal ordering -> compareTo (Java has no </>/<=/>= operator for the Decimal
        // class). The non-Decimal Integer side widens via Decimal.valueOf. ==/!= are left alone:
        // those are object identity/equals semantics handled below, out of scope here.
        if (RELATIONAL.contains(b.op()) && (isDecimal(b.left()) || isDecimal(b.right()))) {
            return "(" + decimalOperand(b.left()) + ".compareTo("
                + decimalOperand(b.right()) + ") " + b.op() + " 0)";
        }
        String l = emitExpr(b.left());
        String r = emitExpr(b.right());
        return switch (b.op()) {
            // Apex == / != are value equality (Java == is reference for objects)
            case "==" -> "java.util.Objects.equals(" + l + ", " + r + ")";
            case "!=" -> "!java.util.Objects.equals(" + l + ", " + r + ")";
            // Apex === / !== are IDENTITY (reference) comparisons: emit Java's reference == / !=
            // directly, BYPASSING the Objects.equals value helper above.
            case "===" -> "(" + l + " == " + r + ")";
            case "!==" -> "(" + l + " != " + r + ")";
            case "&&" -> "(" + l + " && " + r + ")";
            case "||" -> "(" + l + " || " + r + ")";
            default -> "(" + l + " " + b.op() + " " + r + ")";
        };
    }

    private static String mapCallee(String callee) {
        // System.assert is a Java keyword clash -> map to assertTrue
        return callee.equals("System.assert") ? "System.assertTrue" : callee;
    }

    // Java *erasure* signature (name + raw param types, generics dropped) — the javac
    // view, so List<String> and List<Account> collide just like Account and Contact do
    private String javaSig(MethodDecl m) {
        StringBuilder s = new StringBuilder(m.name()).append('(');
        for (int i = 0; i < m.params().size(); i++) {
            if (i > 0) s.append(',');
            String t = mapType(m.params().get(i).type());
            int lt = t.indexOf('<');
            s.append(lt >= 0 ? t.substring(0, lt) : t);
        }
        return s.append(')').toString();
    }

    private String emitNew(New nw) {
        if (mapType(nw.type()).equals("SObject")) {
            String args = emitArgs(nw.args());
            return "new SObject(\"" + base(nw.type()) + "\"" + (args.isEmpty() ? "" : ", " + args) + ")";
        }
        // a user class's constructor: coerce an Integer argument into a Decimal ctor param
        // (Apex widens; Java needs it explicit). Ctor param types are indexed under `(new)#i`.
        return "new " + mapType(nw.type()) + "(" + emitCtorArgs(base(nw.type()), nw.args()) + ")";
    }

    // emitArgs, coercing each Integer arg into the declared type of constructor param #i of
    // `klass` (via the `(new)#i` index). An unknown class/ctor finds nothing and emits each arg
    // unchanged — no behavior change.
    private String emitCtorArgs(String klass, List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceArg(ctorParamTypeOf(klass, i), args.get(i)));
        }
        return String.join(", ", out);
    }

    // The declared Apex type of constructor param #index of a known user class (via the `(new)#i`
    // index), or null. Mirrors paramTypeOf for the constructor key — including AMBIGUOUS -> null
    // (overloaded ctors disagree at this position), so we never coerce into the wrong overload.
    private String ctorParamTypeOf(String klass, int index) {
        if (klass == null) {
            return null;
        }
        java.util.Map<String, String> m = memberTypes.get(base(klass));
        if (m == null && base(klass).contains(".")) {
            m = memberTypes.get(base(klass).substring(base(klass).lastIndexOf('.') + 1));
        }
        String t = m == null ? null : m.get(ctorParamKey(index));
        return isAmbiguous(t) ? null : t;
    }

    private String emitArgs(List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (Expr a : args) out.add(emitExpr(a));
        return String.join(", ", out);
    }

    // emitArgs for the elements of a List/Set literal, widening an Integer element into the declared
    // element type's Decimal/Double slot (new List<Decimal>{ 1, 2 }) — Apex widens; Java won't for a
    // boxed/class element. The element type is the collection's single generic (firstGeneric).
    private String emitListElements(String collectionType, List<Expr> elements) {
        String elemType = firstGeneric(collectionType);
        List<String> out = new ArrayList<>();
        for (Expr e : elements) out.add(coerceNumeric(elemType, e));
        return String.join(", ", out);
    }

    // Like emitArgs, but for a bare same-class method call: coerce an Integer argument into a
    // Decimal where the declared parameter is Decimal (Apex widens; Java needs the conversion).
    // The callee param types come from the current class's member index; an unknown callee
    // (qualified/built-in) finds nothing and emits each arg unchanged — no behavior change.
    private String emitCallArgs(String callee, List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceArg(paramTypeOf(callee, i), args.get(i)));
        }
        return String.join(", ", out);
    }

    // Rewrite an arg-value expression of a TYPED sObject literal so its references to the ENCLOSING
    // instance survive the double-brace anonymous-subclass init block (where bare `this`/inherited
    // names rebind to the anon instance):
    //   - `this` (bare, or rooting `this.field` / `this.m(...)`)  ->  `<EnclosingClass>.this...`
    //   - a bare name that is a current-class FIELD (and NOT shadowed by a param/local)
    //                                                            ->  `<EnclosingClass>.this.<name>`
    // LOCALS and PARAMS stay bare (Java locals can't be shadowed by inherited members; qualifying
    // them would read the wrong symbol). The qualified form is depth-independent, so the rewrite is
    // idempotent; it stops at a NESTED typed literal (its own emitSObject re-applies the rewrite to
    // its own args — no double-qualification). The enclosing class is `typer.currentClass`, which is
    // the SIMPLE name of the class whose method body is being emitted — and exactly the emitted Java
    // name (a top-level class, or a static nested `class Inner` for which javac accepts `Inner.this`).
    private Expr qualifyEnclosing(Expr e) {
        String cls = typer.currentClass;
        if (cls == null) {
            return e; // no enclosing class context (defensive) -> leave untouched
        }
        return qualifyEnclosing(e, cls);
    }

    private Expr qualifyEnclosing(Expr e, String cls) {
        // the qualifier for an UNSHADOWED current-class field read: in a static method (or for a
        // static field in any method) it's the CLASS — `<Cls>.<name>` (correct Java for a static, and
        // it still escapes the anon block's token shadowing); in an instance method an instance field
        // is the enclosing instance — `<Cls>.this.<name>`. `<Cls>.this` itself can't appear in a
        // static method (no enclosing instance), so a bare `this` there is left unrewritten (defensive
        // — Apex's parser rejects `this` in a static method, so this can't occur in valid input).
        Expr qualifiedThis = new Name(cls + ".this");
        Expr classQualifier = new Name(cls);
        return switch (e) {
            // a NESTED typed literal: leave it — emitSObject rewrites its own args (idempotent either
            // way, but stopping here keeps the rewrite shallow and avoids re-walking its subtree).
            case SObjectLit so -> so;
            // bare `this`: only valid in an instance method (-> qualified enclosing); leave bare in a
            // static method (unreachable in valid Apex) rather than emit an illegal `<Cls>.this`.
            case Name n when n.ident().equals("this") -> currentMethodStatic ? n : qualifiedThis;
            // bare field read: qualify only a current-class field that no param/local shadows. A STATIC
            // field (or any field in a static method) is qualified by the CLASS, an instance field in
            // an instance method by the enclosing instance.
            case Name n when fieldTypes.containsKey(n.ident()) && !methodScopeLocals.contains(n.ident()) ->
                (currentMethodStatic || staticFields.contains(n.ident()))
                    ? new Prop(classQualifier, n.ident())
                    : new Prop(qualifiedThis, n.ident());
            case Name n -> n;
            case Prop p -> new Prop(qualifyEnclosing(p.target(), cls), p.name(), p.safe());
            case MethodCall mc -> new MethodCall(qualifyEnclosing(mc.target(), cls), mc.name(),
                mapQualify(mc.args(), cls), mc.safe());
            case Call c -> new Call(c.callee(), mapQualify(c.args(), cls));
            case New nw -> new New(nw.type(), mapQualify(nw.args(), cls));
            case Unary u -> new Unary(u.op(), qualifyEnclosing(u.operand(), cls));
            case Postfix p -> new Postfix(qualifyEnclosing(p.operand(), cls), p.op());
            case Binary b -> new Binary(b.op(), qualifyEnclosing(b.left(), cls), qualifyEnclosing(b.right(), cls));
            case Ternary t -> new Ternary(qualifyEnclosing(t.cond(), cls),
                qualifyEnclosing(t.then(), cls), qualifyEnclosing(t.els(), cls));
            case Index ix -> new Index(qualifyEnclosing(ix.target(), cls), qualifyEnclosing(ix.index(), cls));
            case Cast c -> new Cast(c.type(), qualifyEnclosing(c.expr(), cls));
            case InstanceOf io -> new InstanceOf(qualifyEnclosing(io.expr(), cls), io.type());
            case ListLit l -> new ListLit(l.type(), mapQualify(l.elements(), cls));
            case ArrayNew a -> new ArrayNew(a.elementType(), qualifyEnclosing(a.size(), cls));
            // leaves and shapes with no enclosing-instance refs to lift (Num/Str/Bool/Null/DecimalLit/
            // ClassLit) and Map/Soql whose own keys/binds can't reference the wrapper's bare fields
            // through this literal path -> emit unchanged.
            default -> e;
        };
    }

    private List<Expr> mapQualify(List<Expr> args, String cls) {
        List<Expr> out = new ArrayList<>(args.size());
        for (Expr a : args) out.add(qualifyEnclosing(a, cls));
        return out;
    }

    private String emitSObject(SObjectLit so) {
        // canonical casing for the emitted class name (Apex is case-insensitive): `new account(...)`
        // must emit `new Account(){{ ... }}` to link against the generated `class Account`.
        String base = typedName(base(so.type()));
        if (isTyped(base)) {
            // typed: new Account(Name=x) -> new Account(){{ setName(x); }} (setters type-check).
            // Coerce an Integer literal into a Decimal field's type the same way an assignment
            // does, so `new Opportunity(Amount = 5)` feeds the BigDecimal setter, not an int.
            // The init block is an ANONYMOUS subclass of the typed sObject, so inside it `this` and
            // any bare name that collides with an inherited member (the field-token statics) rebind
            // to the anon instance — an arg value touching the ENCLOSING instance must be qualified
            // (<EnclosingClass>.this...). qualifyEnclosing rewrites those refs; the widen decision
            // still runs on the ORIGINAL expr (the typer can't type the synthetic qualified-this).
            StringBuilder sb = new StringBuilder("new ").append(base).append("(){{");
            for (FieldInit f : so.fields()) {
                sb.append(" set").append(schema.canonicalField(base, f.name())).append('(')
                  .append(coerceNumeric(schema.fieldType(base, f.name()),
                                        f.value(), qualifyEnclosing(f.value()))).append(");");
            }
            return sb.append(" }}").toString();
        }
        // untyped/dynamic SObject: values are stored as Object, but still widen an Integer into
        // a Decimal field so the stored value's runtime type matches Apex (a Decimal, not an int).
        StringBuilder sb = new StringBuilder("new SObject(\"").append(base).append('"');
        for (FieldInit f : so.fields()) {
            sb.append(", \"").append(f.name()).append("\", ")
              .append(coerceNumeric(schema.fieldType(base, f.name()), f.value()));
        }
        return sb.append(')').toString();
    }

    private String emitSoql(Soql q) {
        StringBuilder binds = new StringBuilder();
        for (int i = 0; i < q.binds().size(); i++) {
            if (i > 0) binds.append(", ");
            Bind b = q.binds().get(i);
            binds.append('"').append(b.key()).append("\", ").append(emitExpr(b.value()));
        }
        return "Database.query(\"" + escape(q.query()) + "\", java.util.Map.of(" + binds + "))";
    }

    // At the SOQL/runtime boundary, Database.query returns List<SObject>; re-type it to the
    // declared sObject type via the generated many()/one() wrappers. Non-SOQL inits, or types
    // whose sObject isn't generated, are emitted unchanged (stays List<SObject>/SObject).
    private String emitTypedInit(String declaredType, Expr init) {
        if (init instanceof Soql) {
            String base = base(declaredType);
            // Apex type names are case-insensitive: a `list<contact>` return/var type must still
            // re-type the query result (Contact.many(...)) the same as `List<Contact>`. Match the
            // collection base case-insensitively (the elem isTyped/typedName checks already are).
            if (isCollectionType(base)) {
                String elem = base(firstGeneric(declaredType));
                if (isTyped(elem)) {
                    return typedName(elem) + ".many(" + emitExpr(init) + ")"; // typed list (canonical)
                }
                // untyped list: Database.query already returns List<SObject>, no wrap needed
            } else if (isSObjectName(base)) {
                // single-row SOQL bound to one sObject (Account a = [SELECT ... LIMIT 1]):
                // query returns a List, so take the first row — typed via one(), else get(0)
                return isTyped(base)
                    ? typedName(base) + ".one(" + emitExpr(init) + ")"
                    : emitExpr(init) + ".get(0)";
            }
        }
        return emitExpr(init);
    }

    // Whether a for-each iterable is a List<SObject> the loop var must be re-typed from (via .many()):
    // a SOQL result (Database.query -> List<SObject>) or any expression the typer resolves to a
    // List whose element is the generic SObject (a child-relationship collection, ord.Items__r). A
    // List already typed to the loop's sObject (List<Account>) needs no wrap and isn't matched here.
    private boolean iterableIsSObjectList(Expr iterable) {
        if (iterable instanceof Soql) {
            return true;
        }
        String t = typer.typeOf(iterable);
        return t != null && base(t).equals("List") && base(firstGeneric(t)).equals("SObject");
    }

    // Whether an Apex type is a List/Set/Map whose (single) element is a generated typed sObject —
    // the slot shape the List<SObject> covariance wrap targets (List<Account>, account[]).
    private boolean isTypedSObjectList(String declaredType) {
        return declaredType != null && isCollectionType(base(declaredType))
            && isTyped(base(firstGeneric(declaredType)));
    }

    // Apex List covariance at a typed slot: when the DECLARED target/param type is a typed-sObject
    // list (List<Account>) and `value` types as List<SObject> (a child-relationship read, a generic
    // query helper), re-type each row via the generated wrapper — `Account.many(value)` — exactly as
    // the for-each / direct-SOQL-return sites do. Java generics are invariant, so a bare List<SObject>
    // can't bind a List<Typed> slot directly. Returns the emitted argument text: the .many() wrap when
    // the covariance applies, else `value` unchanged (no behavior change for any other shape).
    private String emitListCovariant(String declaredType, Expr value) {
        if (declaredType != null && isCollectionType(base(declaredType))) {
            String elem = base(firstGeneric(declaredType));
            if (isTyped(elem) && iterableIsSObjectList(value)) {
                return typedName(elem) + ".many(" + emitExpr(value) + ")"; // typed list (canonical)
            }
        }
        return emitExpr(value);
    }

    // the element type of a generic: List<Account> -> Account, Map<Id,Account> -> Account
    private static String firstGeneric(String type) {
        int lt = type.indexOf('<');
        if (lt < 0) {
            return type;
        }
        List<String> parts = splitTopLevel(type.substring(lt + 1, type.lastIndexOf('>')));
        return parts.get(parts.size() - 1).trim();
    }

    // --- type mapping (Apex -> Java)
    private String mapType(String apexType) {
        String t = apexType.trim();
        // Apex arrays are Lists: T[] is exactly List<T> (same type, interchangeable)
        if (t.endsWith("[]")) {
            return "List<" + mapType(t.substring(0, t.length() - 2)) + ">";
        }
        int lt = t.indexOf('<');
        String base = (lt >= 0 ? t.substring(0, lt) : t).replace("[]", "");
        String canon = BUILTINS.get(base.toLowerCase());
        if (canon != null) base = canon; // case-fold built-in type names (decimal -> Decimal)
        // ConnectApi is enormous and fully org-coupled: no surface is modeled. A ConnectApi.* TYPE
        // (a VARIABLE's declared type) falls through to the dynamic SObject below — member reads/
        // writes on it then route through the untyped .get()/.put() path (Object in/out, compiles),
        // exactly the pre-ConnectApi-stub behavior for an unknown dotted type. A STATIC chain rooted
        // at the bare ConnectApi namespace (ConnectApi.Svc.call(...)) is still degraded to
        // ConnectApi.unsupported(...) in emitProp/emitMethodCall — a static can't be an SObject value.
        // ApexPages nested types (Severity/Message) stay qualified onto the runtime ApexPages.
        if (base.startsWith("ApexPages.") && APEXPAGES_TYPES.contains(base.substring("ApexPages.".length())))
            return base;
        if (base.startsWith("Schema.")) base = base.substring("Schema.".length());
        if (base.startsWith("System.")) base = base.substring("System.".length()); // System.Http -> Http
        if (base.regionMatches(true, 0, "dom.", 0, 4)) return base.substring(4); // Dom.XmlNode -> XmlNode
        // Database.SaveResult / Database.Error stay qualified (nested classes on runtime Database);
        // Database.Batchable<sObject> keeps its generic so it implements Batchable<T> faithfully.
        if (base.startsWith("Database.") && DATABASE_TYPES.contains(base.substring("Database.".length())))
            return base + runtimeGenerics(base.substring("Database.".length()), lt, t);
        // Messaging.SingleEmailMessage / Messaging.EmailFileAttachment stay qualified (nested classes
        // on the runtime Messaging), mirroring the Database.* mechanism above.
        if (base.startsWith("Messaging.") && MESSAGING_TYPES.contains(base.substring("Messaging.".length())))
            return base;
        if (SCHEMA_TYPES.contains(base)) return base; // Schema.SObjectType -> runtime SObjectType
        // Apex lets System-namespace types whose runtime home is a Database nested type appear
        // BARE: `Savepoint sp = Database.setSavepoint();` — qualify them to the nested class.
        if (base.equalsIgnoreCase("Savepoint")) return "Database.Savepoint";
        if (base.equalsIgnoreCase("QueryLocatorIterator")) return "Database.QueryLocatorIterator";
        // native System types, case-insensitive (Apex): HTTPRequest -> HttpRequest, blob -> Blob.
        // Iterable<T>/Iterator<T> keep their generic; the other runtime types carry none.
        String runtimeCanon = RUNTIME_CANON.get(base.toLowerCase(java.util.Locale.ROOT));
        if (runtimeCanon != null) return runtimeCanon + runtimeGenerics(runtimeCanon, lt, t);
        if (JAVA_SAME.contains(base)) return base;
        if (base.equals("Decimal") || base.equals("Date")
            || base.equals("Datetime") || base.equals("Time")) return base; // runtime types
        if (base.equals("Id")) return "String";
        if (COLLECTIONS.contains(base)) {
            if (lt < 0) {
                return base;
            }
            String generics = mapGenerics(t.substring(lt));
            // Apex List<Object>/Set<Object> accept any List/Set (covariant to Object); Java is
            // invariant, so emit the raw type to mirror Apex's permissiveness (a List<String>
            // can then be passed where a List<Object> is expected, as in Apex).
            // List<SObject>/Set<SObject> are likewise covariant — any List<Account>/List<Contact>
            // is accepted where List<SObject> is — so the same raw emission applies.
            return generics.equals("<Object>") || generics.equals("<SObject>") ? base : base + generics;
        }
        if (innerTypes.contains(base)) { // nested class: emit by its simple Java name
            return base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
        }
        if (userClasses.contains(base)) return base;
        // generated typed sObject class — emit the CANONICAL casing (Apex is case-insensitive, so a
        // `account` decl resolves here and must emit `Account` to link against `class Account`).
        String canonicalSObject = typedSObjects.canonical(base);
        if (canonicalSObject != null) return canonicalSObject;
        // a qualified type A.B whose outer A is a known class: an inner-class reference.
        // Apex inner classes transpile to Java static nested classes, so A.B stays A.B
        // (e.g. SomeProxy.ResponseElement) instead of collapsing to the dynamic SObject.
        int dot = base.indexOf('.');
        if (dot > 0 && userClasses.contains(base.substring(0, dot))) return base;
        // base Exception and platform exceptions (DmlException, QueryException, …)
        // collapse onto the runtime base; user-defined ones keep their name above
        if (base.equals("Exception") || base.endsWith("Exception")) return "ApexException";
        return "SObject"; // unknown non-primitive => assume sObject
    }

    // The mapped <...> suffix for a recognized generic runtime type (Batchable/Iterable/Iterator),
    // or "" for any other type or when no generic was written. `simple` is the type's simple name
    // (Database. prefix already stripped); `lt` is the '<' index in the original `t`, or < 0.
    private String runtimeGenerics(String simple, int lt, String t) {
        if (lt < 0 || !GENERIC_RUNTIME_TYPES.contains(simple)) return "";
        return mapGenerics(t.substring(lt));
    }

    private String mapGenerics(String generic) {
        String inner = generic.substring(1, generic.length() - 1);
        StringBuilder sb = new StringBuilder("<");
        List<String> parts = splitTopLevel(inner);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(mapType(parts.get(i)));
        }
        return sb.append('>').toString();
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(s.substring(last));
        return parts;
    }

    private static String base(String type) {
        int lt = type.indexOf('<');
        return (lt >= 0 ? type.substring(0, lt) : type).replace("[]", "");
    }

    private static int countNewlines(CharSequence s) {
        int n = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '\n') n++;
        }
        return n;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
