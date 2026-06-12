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
        "import alloyx.runtime.UserInfo;",
        "import alloyx.runtime.Strings;",
        "import alloyx.runtime.Type;",
        "import alloyx.runtime.SObjectType;",
        "import alloyx.runtime.DescribeSObjectResult;",
        "import alloyx.runtime.Pattern;",
        "import alloyx.runtime.Matcher;",
        "import alloyx.runtime.LoggingLevel;",
        "import alloyx.runtime.Limits;",
        "import alloyx.runtime.Http;",
        "import alloyx.runtime.HttpRequest;",
        "import alloyx.runtime.HttpResponse;",
        "import alloyx.runtime.Blob;",
        "import alloyx.runtime.EncodingUtil;",
        "import alloyx.runtime.Trigger;",
        "import alloyx.runtime.Test;",
        "import alloyx.runtime.dom.Document;",
        "import alloyx.runtime.dom.XmlNode;",
        "import alloyx.runtime.dom.XmlNodeType;");

    private static final Set<String> JAVA_SAME =
        Set.of("Integer", "Long", "Boolean", "String", "Object", "Double", "void");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Map");
    // Apex Schema namespace types backed by runtime classes (not dynamic sObjects)
    private static final Set<String> SCHEMA_TYPES = Set.of("SObjectType", "DescribeSObjectResult");
    // native Apex System types backed by runtime classes (not dynamic sObjects)
    private static final Set<String> RUNTIME_TYPES = Set.of("Pattern", "Matcher", "LoggingLevel", "Limits",
        "Http", "HttpRequest", "HttpResponse", "Blob", "EncodingUtil", "Trigger", "Test");
    // Database namespace result types: nested classes on the runtime Database, kept qualified
    private static final Set<String> DATABASE_TYPES =
        Set.of("SaveResult", "UpsertResult", "DeleteResult", "Error");
    // Apex is case-insensitive, so HTTPRequest == HttpRequest and blob == Blob; map a lowercased
    // native type name back to its canonical runtime class.
    private static final java.util.Map<String, String> RUNTIME_CANON = lowerIndex(RUNTIME_TYPES);

    private static java.util.Map<String, String> lowerIndex(Set<String> names) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String n : names) m.put(n.toLowerCase(java.util.Locale.ROOT), n);
        return m;
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
        java.util.Map.entry("datetime", "Datetime"), java.util.Map.entry("time", "Time"),
        java.util.Map.entry("list", "List"), java.util.Map.entry("set", "Set"),
        java.util.Map.entry("map", "Map"), java.util.Map.entry("system", "System"),
        java.util.Map.entry("math", "Math"), java.util.Map.entry("database", "Database"),
        java.util.Map.entry("json", "JSON"), java.util.Map.entry("userinfo", "UserInfo"));

    private static final Set<String> SCALARS = Set.of("String", "Integer", "Long", "Double",
        "Decimal", "Boolean", "Date", "Datetime", "Time", "Id", "Blob", "Object", "void");

    private final Set<String> userClasses;
    private final SchemaProvider schema;
    // sObjects with a generated typed class (described via sync); empty -> everything
    // stays the generic dynamic SObject, byte-for-byte the pre-typing behavior
    private final Set<String> typedSObjects;
    // light type tracking so sObject field access (a.Name) becomes a.get("Name").
    // fieldTypes is swapped per class body (outer vs inner) as emission descends.
    private java.util.Map<String, String> fieldTypes = new java.util.HashMap<>();
    private final java.util.Map<String, String> locals = new java.util.HashMap<>();
    private String currentReturnType = "void";
    // inner (nested) class names — both simple (Inner) and qualified (Outer.Inner) —
    // so references resolve as a user type, never the fallback dynamic SObject
    private final Set<String> innerTypes = new java.util.HashSet<>();
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
        this.typedSObjects = typedSObjects;
        this.memberIndex = memberIndex;
        // Copy the global member-type index so the per-class re-seed (indexMemberTypes) can replace
        // the current class's entry without mutating the shared map other Transpilers read from.
        this.memberTypes = new java.util.HashMap<>(memberTypes);
        this.typer = new ExprTyper(schema, typedSObjects, userClasses, innerTypes, this.memberTypes,
            locals, () -> fieldTypes);
    }

    private boolean isSObject(Expr e) {
        return sObjectTypeOf(e) != null;
    }

    /** This sObject has a generated typed class (so field access is a typed getter/setter). */
    private boolean isTyped(String base) {
        return base != null && typedSObjects.contains(base);
    }

    /** This name denotes an sObject — either a generated typed one or the generic SObject. */
    private boolean isSObjectName(String base) {
        return typedSObjects.contains(base) || mapType(base).equals("SObject");
    }

    // the sObject API name an expression evaluates to (Account, Contact, ...), or null.
    // Relationship hops (a.Owner) are resolved via the schema describe when available.
    private String sObjectTypeOf(Expr e) {
        return switch (e) {
            case Name n -> {
                String t = n.ident().equals("this") ? null : locals.get(n.ident());
                if (t == null && !n.ident().equals("this")) {
                    // an inherited field read without `this.`: not in locals (only the current
                    // body's own fields are) — resolve through the member index's extends walk
                    t = memberType(typer.currentClass, n.ident());
                }
                yield t != null && isSObjectName(base(t)) ? base(t) : null;
            }
            case Cast c -> isSObjectName(base(c.type())) ? base(c.type()) : null;
            case New nw -> isSObjectName(base(nw.type())) ? base(nw.type()) : null;
            case SObjectLit so -> base(so.type());
            case Prop p -> {
                // this.<field>: resolve to the field's own declared type. Fields are
                // copied into `locals`, but the target `this` resolves to null above,
                // so this.lead.CNPJ__c would miss the getter without this. An INHERITED
                // field isn't in locals (only the current body's fields are), so fall
                // back to the member-type index, which walks the `extends` chain.
                if (p.target() instanceof Name tn && tn.ident().equals("this")) {
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
    // Decimal-widen on an Assign-to-Prop whose target is another class's field (cart.cost = 333).
    // Excludes a `this`-rooted target: that's already handled by the field-local widen paths.
    private String propFieldType(Prop p) {
        if (p.target() instanceof Name tn && tn.ident().equals("this")) {
            return null;
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
    private static String ctorParamKey(int i) {
        return "(new)#" + i;
    }

    // Index a class's members (fields + method return types + param types) by lowercase name,
    // keyed by class simple name, into `into`, so the central typer can type a field read / method
    // call and coerce a Decimal-param call argument. Recurses into inners (indexed by simple name,
    // matched as Outer.Inner too). Param types use a `(name)#i` key to avoid colliding with a
    // same-named field/method; the lookup the typer needs by name is the return type. The
    // superclass simple name is recorded under EXTENDS_KEY so the lookups can climb the chain to
    // an inherited member. Static so Workspace can build the SAME index over EVERY class in the
    // compile set (cross-class typing).
    static void populateMemberTypes(ClassDecl cls,
            java.util.Map<String, java.util.Map<String, String>> into) {
        java.util.Map<String, String> m =
            into.computeIfAbsent(cls.name(), k -> new java.util.HashMap<>());
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
            m.putIfAbsent(f.name().toLowerCase(java.util.Locale.ROOT), f.type());
        }
        for (MethodDecl md : cls.methods()) {
            String mn = md.name().toLowerCase(java.util.Locale.ROOT);
            boolean isCtor = md.name().equals(cls.name());
            // ctor has no return type; skip so `new Foo()` typing stays via New
            if (!isCtor) {
                m.putIfAbsent(mn, md.returnType());
            }
            // params keyed by position so a call site can recover the declared param type;
            // a constructor's go under `(new)#i` so `new Foo(args)` can coerce a Decimal arg.
            for (int i = 0; i < md.params().size(); i++) {
                m.putIfAbsent("(" + mn + ")#" + i, md.params().get(i).type());
                if (isCtor) {
                    m.putIfAbsent(ctorParamKey(i), md.params().get(i).type());
                }
            }
        }
        for (ClassDecl inner : cls.inners()) populateMemberTypes(inner, into);
    }

    // The declared Apex type of param #index of a bare same-class method (by lowercase method
    // name), or null. Lets the call site coerce a Decimal-typed argument (Apex widens Integer
    // -> Decimal; Java needs it explicit).
    private String paramTypeOf(String method, int index) {
        return paramTypeOf(typer.currentClass, method, index);
    }

    // Same, but for a method on a known user class (e.g. an explicit `obj.m(...)` where obj's
    // static type is in our member index). Unknown class/method -> null (no coercion).
    private String paramTypeOf(String klass, String method, int index) {
        if (klass == null) {
            return null;
        }
        java.util.Map<String, String> m = memberTypes.get(base(klass));
        if (m == null && base(klass).contains(".")) {
            m = memberTypes.get(base(klass).substring(base(klass).lastIndexOf('.') + 1));
        }
        return m == null ? null : m.get("(" + method.toLowerCase(java.util.Locale.ROOT) + ")#" + index);
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
        for (Field f : cls.fields()) myFields.put(f.name(), f.type());
        this.fieldTypes = myFields;
        String prevClass = typer.currentClass;
        typer.currentClass = cls.name(); // `this`-rooted member typing resolves against this body

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
            if (f.init() != null) sb.append(" = ").append(coerceDecimal(f.type(), f.init()));
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
            for (Param p : m.params()) locals.put(p.name(), p.type());
            currentReturnType = isCtor ? "void" : m.returnType();
            sb.append(member).append("public ");
            if (m.isStatic()) sb.append("static ");
            if (!isCtor) sb.append(mapType(m.returnType())).append(' ');
            sb.append(m.name()).append('(');
            for (int i = 0; i < m.params().size(); i++) {
                if (i > 0) sb.append(", ");
                Param p = m.params().get(i);
                sb.append(mapType(p.type())).append(' ').append(p.name());
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
                locals.put(v.name(), v.type());
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
                } else if (mapType(v.type()).equals("Decimal") && isIntegerExpr(v.init())) {
                    // Apex widens Integer to Decimal; Java needs the explicit conversion
                    sb.append(indent).append("Decimal ").append(v.name())
                      .append(" = ").append(coerceDecimal(v.type(), v.init())).append(";\n");
                } else {
                    sb.append(indent).append("var ").append(v.name())
                      .append(" = ").append(emitExpr(v.init())).append(";\n");
                }
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
                          .append(coerceDecimal(schema.fieldType(parent, pr.name()), a.value())).append(");\n");
                    } else {
                        sb.append(indent).append(emitExpr(pr.target())).append(".put(\"")
                          .append(pr.name()).append("\", ").append(emitExpr(a.value())).append(");\n");
                    }
                } else if (a.value() instanceof Soql && a.target() instanceof Name sn
                        && locals.containsKey(sn.ident())) {
                    // x = [SELECT...] -> re-type the query result to x's declared type
                    sb.append(indent).append(sn.ident()).append(" = ")
                      .append(emitTypedInit(locals.get(sn.ident()), a.value())).append(";\n");
                } else if (a.target() instanceof Name nm && locals.containsKey(nm.ident())
                        && a.value() instanceof Prop vp && isSObject(vp.target())
                        && !isTyped(sObjectTypeOf(vp.target()))) {
                    // typed var = sObject field -> cast Object back to the var's declared type
                    String t = mapType(locals.get(nm.ident()));
                    sb.append(indent).append(nm.ident()).append(" = (").append(t).append(") ")
                      .append(emitExpr(a.value())).append(";\n");
                } else if (a.target() instanceof Name dnm && locals.containsKey(dnm.ident())
                        && mapType(locals.get(dnm.ident())).equals("Decimal") && isIntegerExpr(a.value())) {
                    // Apex widens Integer to Decimal on assignment; Java needs it explicit
                    sb.append(indent).append(dnm.ident()).append(" = ")
                      .append(coerceDecimal(locals.get(dnm.ident()), a.value())).append(";\n");
                } else if (a.target() instanceof Prop pp && propFieldType(pp) != null
                        && isIntegerExpr(a.value())
                        && mapType(propFieldType(pp)).equals("Decimal")) {
                    // cross-class user field of Decimal type (cart.cost = 333): widen the Integer,
                    // now that the member-type index makes the field's declared type resolvable
                    sb.append(indent).append(emitExpr(a.target())).append(" = ")
                      .append(coerceDecimal(propFieldType(pp), a.value())).append(";\n");
                } else {
                    sb.append(indent).append(emitExpr(a.target())).append(" = ")
                      .append(emitExpr(a.value())).append(";\n");
                }
            }
            case ExprStmt e -> sb.append(indent).append(emitExpr(e.expr())).append(";\n");
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
                    sb.append(' ').append(coerceDecimal(currentReturnType, r.value()));
                }
                sb.append(";\n");
            }
            case If iff -> {
                sb.append(indent).append("if (").append(emitExpr(iff.cond())).append(") {\n");
                for (Stmt st : iff.thenBody()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}");
                if (!iff.elseBody().isEmpty()) {
                    sb.append(" else {\n");
                    for (Stmt st : iff.elseBody()) emitStmt(st, indent + "    ", sb);
                    sb.append(indent).append("}");
                }
                sb.append('\n');
            }
            case While w -> {
                sb.append(indent).append("while (").append(emitExpr(w.cond())).append(") {\n");
                for (Stmt st : w.body()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}\n");
            }
            case ForEach fe -> {
                locals.put(fe.name(), fe.type());
                // for (Account a : [SELECT...]) -> iterate the re-typed query result
                String iterable = fe.iterable() instanceof Soql && isTyped(base(fe.type()))
                    ? base(fe.type()) + ".many(" + emitExpr(fe.iterable()) + ")"
                    : emitExpr(fe.iterable());
                sb.append(indent).append("for (").append(mapType(fe.type())).append(' ')
                  .append(fe.name()).append(" : ").append(iterable).append(") {\n");
                for (Stmt st : fe.body()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}\n");
            }
            case For f -> {
                sb.append(indent).append("for (")
                  .append(f.init() == null ? "" : emitForClause(f.init())).append("; ")
                  .append(f.cond() == null ? "" : emitExpr(f.cond())).append("; ")
                  .append(f.update() == null ? "" : emitForClause(f.update())).append(") {\n");
                for (Stmt st : f.body()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}\n");
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
                for (Stmt st : g.body()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}\n");
            }
            case Try tr -> {
                sb.append(indent).append("try {\n");
                for (Stmt st : tr.body()) emitStmt(st, indent + "    ", sb);
                sb.append(indent).append("}");
                Set<String> seenCatch = new java.util.HashSet<>();
                for (Catch c : tr.catches()) {
                    String ct = mapType(c.type());
                    if (!seenCatch.add(ct)) continue; // dedupe catches collapsing to one Java type
                    sb.append(" catch (").append(ct).append(' ').append(c.name()).append(") {\n");
                    for (Stmt st : c.body()) emitStmt(st, indent + "    ", sb);
                    sb.append(indent).append("}");
                }
                if (!tr.finallyBody().isEmpty()) {
                    sb.append(" finally {\n");
                    for (Stmt st : tr.finallyBody()) emitStmt(st, indent + "    ", sb);
                    sb.append(indent).append("}");
                }
                sb.append('\n');
            }
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
                : "new " + mapType(l.type()) + "(java.util.Arrays.asList(" + emitArgs(l.elements()) + "))";
            case MapLit m -> emitMapLit(m);
            case Prop p -> emitProp(p);
            case MethodCall mc -> emitMethodCall(mc);
            case Cast c -> emitCast(c);
            case InstanceOf io -> "(" + emitExpr(io.expr()) + " instanceof " + mapType(io.type()) + ")";
            case ClassLit cl -> {
                String b = cl.type();
                int lt = b.indexOf('<');
                yield mapType(lt >= 0 ? b.substring(0, lt) : b) + ".class"; // Java has no List<X>.class
            }
        };
    }

    // Apex numeric-narrowing casts on a Decimal -> the runtime Decimal (a BigDecimal) can't be
    // Java-cast to a primitive box, so route to the right extraction method. Only when the source
    // expr actually types as Decimal; otherwise the regular cast path applies (no behavior change).
    private static final java.util.Map<String, String> DECIMAL_NARROW = java.util.Map.of(
        "Integer", "intValue", "Long", "longValue", "Double", "doubleValue");

    private String emitCast(Cast c) {
        // (Integer) someDecimal / (Long) / (Double): emit dec.intValue()/longValue()/doubleValue()
        // instead of an illegal Java cast of a BigDecimal to a primitive box.
        String narrow = DECIMAL_NARROW.get(mapType(c.type()));
        if (narrow != null && isDecimal(c.expr())) {
            return "(" + emitExpr(c.expr()) + ")." + narrow + "()";
        }
        // (List<Account>) queryResult: the query builder returns List<SObject>; re-type and
        // wrap its rows into a real List<Account> (fixes both javac's invariance and the
        // runtime element type, exactly like a direct SOQL bound to List<Account>)
        if (base(c.type()).equals("List")) {
            String elem = base(firstGeneric(c.type()));
            if (isTyped(elem)) {
                return elem + ".many(" + emitExpr(c.expr()) + ")";
            }
        }
        String tt = mapType(c.type());
        // Java generics are invariant — a cast between parameterized collections
        // (List<SObject> vs List<Account>) must go via Object, mirroring Apex's
        // permissive collection downcast
        return tt.contains("<")
            ? "((" + tt + ")(Object) " + emitExpr(c.expr()) + ")"
            : "((" + tt + ") " + emitExpr(c.expr()) + ")";
    }

    private String emitProp(Prop p) {
        // native Apex enum member access is case-insensitive (LoggingLevel.Info); the runtime
        // enum constants are canonical UPPER_CASE, so fold the member to match
        if (p.target() instanceof Name tn) {
            String runtimeEnum = runtimeEnum(tn.ident());
            if (runtimeEnum != null) {
                return runtimeEnum + "." + p.name().toUpperCase(java.util.Locale.ROOT);
            }
        }
        String access = fieldAccess(p);
        if (p.safe()) { // Apex a?.b -> (a == null ? null : a.b)
            return "(" + emitExpr(p.target()) + " == null ? null : " + access + ")";
        }
        return access;
    }

    private String fieldAccess(Prop p) {
        // Apex trigger context: Trigger.new (a Java keyword) and friends map onto the
        // runtime Trigger stub's static fields (case-insensitive, like Apex).
        if (p.target() instanceof Name tn && tn.ident().equalsIgnoreCase("Trigger")) {
            String m = p.name().toLowerCase(java.util.Locale.ROOT);
            return "Trigger." + TRIGGER_MEMBERS.getOrDefault(m, p.name());
        }
        String parent = sObjectTypeOf(p.target());
        if (parent == null) {
            // not an sObject — resolve the member case-insensitively against the target's
            // declared class (Apex: incomingItem.Name == incomingItem.name)
            return emitExpr(p.target()) + "." + canonicalMember(declaredTypeOf(p.target()), p.name());
        }
        if (isTyped(parent)) {
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

    /** The declared (Apex) type of a target we can read cheaply: a local/param, or this.field. */
    private String declaredTypeOf(Expr e) {
        if (e instanceof Name n && !n.ident().equals("this")) {
            String t = locals.get(n.ident());
            if (t != null) {
                return t;
            }
            // a bare known class name -> static member access (CCConstants.SOME_CONST)
            if (memberIndex.containsKey(n.ident())) {
                return n.ident();
            }
        }
        if (e instanceof Prop pr && pr.target() instanceof Name tn && tn.ident().equals("this")) {
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
        String call = methodCall(mc);
        if (mc.safe()) { // Apex a?.m() -> (a == null ? null : a.m())
            return "(" + emitExpr(mc.target()) + " == null ? null : " + call + ")";
        }
        return call;
    }

    private String methodCall(MethodCall mc) {
        String name = mc.name();
        // built-in static call (Apex is case-insensitive): canonicalize the type name
        // and lower-case the method's first char so Date.ValueOf -> Date.valueOf, etc.
        if (mc.target() instanceof Name n) {
            String canon = BUILTINS.get(n.ident().toLowerCase());
            if (canon != null) {
                String type = canon.equals("String") ? "Strings" : canon; // String statics -> helper
                String method = canon.equals("System") && name.equalsIgnoreCase("assert")
                    ? "assertTrue" : lowerFirst(name);
                return type + "." + method + "(" + emitArgs(mc.args()) + ")";
            }
        }
        // Apex String instance methods Java lacks -> Strings.method(theString, args)
        if (APEX_STRING_METHODS.contains(name) && isString(mc.target())) {
            String args = emitArgs(mc.args());
            return "Strings." + name + "(" + emitExpr(mc.target())
                + (args.isEmpty() ? "" : ", " + args) + ")";
        }
        // a method on a known user-class instance: coerce an Integer arg into a Decimal param
        // (Apex widens). The target's static type drives the param lookup; unknown -> unchanged.
        String args = emitMethodArgs(typer.typeOf(mc.target()), name, mc.args());
        return emitExpr(mc.target()) + "." + name + "(" + args + ")";
    }

    // emitArgs, coercing each Integer arg into the declared param type of `klass.method`.
    private String emitMethodArgs(String klass, String method, List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceDecimal(paramTypeOf(klass, method, i), args.get(i)));
        }
        return String.join(", ", out);
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

    private String emitMapLit(MapLit m) {
        if (m.keys().isEmpty()) {
            return "new " + mapType(m.type()) + "()";
        }
        // double-brace init: works for any key/value type without a runtime factory
        StringBuilder sb = new StringBuilder("new ").append(mapType(m.type())).append("(){{");
        for (int i = 0; i < m.keys().size(); i++) {
            sb.append(" put(").append(emitExpr(m.keys().get(i))).append(", ")
              .append(emitExpr(m.values().get(i))).append(");");
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

    // Apex widens Integer to Decimal implicitly; Java won't (Decimal is a class). Where the
    // expected type is Decimal and the value is an Integer, wrap it so the assignment/return
    // type-checks. Everywhere else the value is emitted unchanged (no behavior change).
    private String coerceDecimal(String expectedApexType, Expr value) {
        // a ternary in a Decimal context (Decimal d = c ? 0 : 1): widen PER BRANCH rather than
        // wrap the whole conditional in Decimal.valueOf(Object) — keeps each branch statically
        // typed. emitTernary already widens a mixed Decimal/Integer ternary on its own; this adds
        // the all-Integer case, which only needs widening because the surrounding type is Decimal.
        if (value instanceof Ternary t && isDecimalType(expectedApexType)) {
            return "(" + emitExpr(t.cond())
                + " ? " + coerceDecimal(expectedApexType, t.then())
                + " : " + coerceDecimal(expectedApexType, t.els()) + ")";
        }
        if (typer.needsDecimalWiden(expectedApexType, value)) {
            return "Decimal.valueOf(" + emitExpr(value) + ")";
        }
        return emitExpr(value);
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
            out.add(coerceDecimal(ctorParamTypeOf(klass, i), args.get(i)));
        }
        return String.join(", ", out);
    }

    // The declared Apex type of constructor param #index of a known user class (via the `(new)#i`
    // index), or null. Mirrors paramTypeOf for the constructor key.
    private String ctorParamTypeOf(String klass, int index) {
        if (klass == null) {
            return null;
        }
        java.util.Map<String, String> m = memberTypes.get(base(klass));
        if (m == null && base(klass).contains(".")) {
            m = memberTypes.get(base(klass).substring(base(klass).lastIndexOf('.') + 1));
        }
        return m == null ? null : m.get(ctorParamKey(index));
    }

    private String emitArgs(List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (Expr a : args) out.add(emitExpr(a));
        return String.join(", ", out);
    }

    // Like emitArgs, but for a bare same-class method call: coerce an Integer argument into a
    // Decimal where the declared parameter is Decimal (Apex widens; Java needs the conversion).
    // The callee param types come from the current class's member index; an unknown callee
    // (qualified/built-in) finds nothing and emits each arg unchanged — no behavior change.
    private String emitCallArgs(String callee, List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceDecimal(paramTypeOf(callee, i), args.get(i)));
        }
        return String.join(", ", out);
    }

    private String emitSObject(SObjectLit so) {
        String base = base(so.type());
        if (isTyped(base)) {
            // typed: new Account(Name=x) -> new Account(){{ setName(x); }} (setters type-check).
            // Coerce an Integer literal into a Decimal field's type the same way an assignment
            // does, so `new Opportunity(Amount = 5)` feeds the BigDecimal setter, not an int.
            StringBuilder sb = new StringBuilder("new ").append(base).append("(){{");
            for (FieldInit f : so.fields()) {
                sb.append(" set").append(schema.canonicalField(base, f.name())).append('(')
                  .append(coerceDecimal(schema.fieldType(base, f.name()), f.value())).append(");");
            }
            return sb.append(" }}").toString();
        }
        // untyped/dynamic SObject: values are stored as Object, but still widen an Integer into
        // a Decimal field so the stored value's runtime type matches Apex (a Decimal, not an int).
        StringBuilder sb = new StringBuilder("new SObject(\"").append(base).append('"');
        for (FieldInit f : so.fields()) {
            sb.append(", \"").append(f.name()).append("\", ")
              .append(coerceDecimal(schema.fieldType(base, f.name()), f.value()));
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
            if (COLLECTIONS.contains(base)) {
                String elem = base(firstGeneric(declaredType));
                if (isTyped(elem)) {
                    return elem + ".many(" + emitExpr(init) + ")"; // typed list
                }
                // untyped list: Database.query already returns List<SObject>, no wrap needed
            } else if (isSObjectName(base)) {
                // single-row SOQL bound to one sObject (Account a = [SELECT ... LIMIT 1]):
                // query returns a List, so take the first row — typed via one(), else get(0)
                return isTyped(base)
                    ? base + ".one(" + emitExpr(init) + ")"
                    : emitExpr(init) + ".get(0)";
            }
        }
        return emitExpr(init);
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
        if (base.startsWith("Schema.")) base = base.substring("Schema.".length());
        if (base.startsWith("System.")) base = base.substring("System.".length()); // System.Http -> Http
        if (base.regionMatches(true, 0, "dom.", 0, 4)) return base.substring(4); // Dom.XmlNode -> XmlNode
        // Database.SaveResult / Database.Error stay qualified (nested classes on runtime Database)
        if (base.startsWith("Database.") && DATABASE_TYPES.contains(base.substring("Database.".length())))
            return base;
        if (SCHEMA_TYPES.contains(base)) return base; // Schema.SObjectType -> runtime SObjectType
        // native System types, case-insensitive (Apex): HTTPRequest -> HttpRequest, blob -> Blob
        String runtimeCanon = RUNTIME_CANON.get(base.toLowerCase(java.util.Locale.ROOT));
        if (runtimeCanon != null) return runtimeCanon;
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
        if (typedSObjects.contains(base)) return base; // generated typed sObject class
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
