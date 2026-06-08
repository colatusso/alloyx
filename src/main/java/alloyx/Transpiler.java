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
    record Result(String className, String source) {}

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
        "import alloyx.runtime.Limits;");

    private static final Set<String> JAVA_SAME =
        Set.of("Integer", "Long", "Boolean", "String", "Object", "Double", "void");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Map");
    // Apex Schema namespace types backed by runtime classes (not dynamic sObjects)
    private static final Set<String> SCHEMA_TYPES = Set.of("SObjectType", "DescribeSObjectResult");
    // native Apex System types backed by runtime classes (not dynamic sObjects)
    private static final Set<String> RUNTIME_TYPES = Set.of("Pattern", "Matcher", "LoggingLevel", "Limits");
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

    private Transpiler(Set<String> userClasses, SchemaProvider schema, Set<String> typedSObjects) {
        this.userClasses = userClasses;
        this.schema = schema;
        this.typedSObjects = typedSObjects;
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
                yield t != null && isSObjectName(base(t)) ? base(t) : null;
            }
            case Cast c -> isSObjectName(base(c.type())) ? base(c.type()) : null;
            case New nw -> isSObjectName(base(nw.type())) ? base(nw.type()) : null;
            case SObjectLit so -> base(so.type());
            case Prop p -> {
                String parent = sObjectTypeOf(p.target());
                if (parent == null) {
                    yield null;
                }
                String ft = schema.fieldType(parent, p.name());
                yield ft != null && !SCALARS.contains(ft) ? ft : null; // a relationship field
            }
            default -> null;
        };
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
        return new Transpiler(userClasses, schema, typedSObjects).emitClass(cls);
    }

    private Result emitClass(ClassDecl cls) {
        registerInnerTypes(cls);
        StringBuilder sb = new StringBuilder();
        sb.append(IMPORTS).append("\n\n");
        // the Apex->Java generics bridging (raw/Object casts) is intentional; hide the
        // unchecked notes — they're noise to the end user, not actionable
        sb.append("@SuppressWarnings(\"unchecked\")\n");
        sb.append("public ");
        emitClassBody(cls, java.util.Map.of(), "", sb);
        return new Result(cls.name(), sb.toString());
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

        sb.append(iface ? "interface " : "class ").append(cls.name());
        sb.append(emitTypeRelations(cls, iface));
        sb.append(" {\n");
        String member = indent + "    ";
        String stmt = member + "    ";

        for (Field f : cls.fields()) {
            sb.append(member);
            if (f.isStatic()) sb.append("static ");
            sb.append(mapType(f.type())).append(' ').append(f.name());
            if (f.init() != null) sb.append(" = ").append(emitExpr(f.init()));
            sb.append(";\n");
        }

        // Apex implicitly gives exception subclasses the standard constructors;
        // inject them so `new MyException('msg')` works, when none are declared
        boolean isException = cls.superclass() != null
            && (cls.superclass().equals("Exception") || cls.superclass().endsWith("Exception"));
        boolean hasCtor = cls.methods().stream().anyMatch(m -> m.name().equals(cls.name()));
        if (!iface && isException && !hasCtor) {
            sb.append('\n');
            sb.append(member).append("public ").append(cls.name()).append("() { super(); }\n");
            sb.append(member).append("public ").append(cls.name())
              .append("(String message) { super(message); }\n");
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
    }

    // --- statements
    private void emitStmt(Stmt s, String indent, StringBuilder sb) {
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
                          .append(emitExpr(a.value())).append(");\n");
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
                    sb.append(' ').append(emitExpr(r.value()));
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
            case Unary u -> u.op().equals("++") || u.op().equals("--")
                ? u.op() + emitExpr(u.operand())
                : "(" + u.op() + emitExpr(u.operand()) + ")";
            case Postfix p -> emitExpr(p.operand()) + p.op();
            case Binary b -> emitBinary(b);
            case Ternary t -> "(" + emitExpr(t.cond()) + " ? " + emitExpr(t.then())
                + " : " + emitExpr(t.els()) + ")";
            case Call c -> mapCallee(c.callee()) + "(" + emitArgs(c.args()) + ")";
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

    private String emitCast(Cast c) {
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
        String parent = sObjectTypeOf(p.target());
        if (parent == null) {
            return emitExpr(p.target()) + "." + p.name(); // regular Java field/member
        }
        if (isTyped(parent)) {
            // typed sObject: a.Name -> a.getName() (javac checks the field exists + its type)
            return emitExpr(p.target()) + ".get" + schema.canonicalField(parent, p.name()) + "()";
        }
        String get = emitExpr(p.target()) + ".get(\"" + p.name() + "\")";
        String ft = schema.fieldType(parent, p.name());
        return ft != null ? "((" + mapType(ft) + ") " + get + ")" : get; // typed via describe, else Object
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
        return emitExpr(mc.target()) + "." + name + "(" + emitArgs(mc.args()) + ")";
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

    // a String-typed operand (so '+' is concatenation, never Decimal arithmetic)
    private boolean isString(Expr e) {
        return switch (e) {
            case Str ignored -> true;
            case Name n -> locals.containsKey(n.ident()) && mapType(locals.get(n.ident())).equals("String");
            case Binary b -> b.op().equals("+") && (isString(b.left()) || isString(b.right()));
            default -> false;
        };
    }

    private boolean isDecimal(Expr e) {
        return switch (e) {
            case DecimalLit ignored -> true;
            case Name n -> locals.containsKey(n.ident()) && mapType(locals.get(n.ident())).equals("Decimal");
            case Cast c -> mapType(c.type()).equals("Decimal");
            case Prop p -> {
                String obj = sObjectTypeOf(p.target());
                yield obj != null && "Decimal".equals(schema.fieldType(obj, p.name()));
            }
            case Binary b -> ARITH.contains(b.op()) && !isString(b.left()) && !isString(b.right())
                && (isDecimal(b.left()) || isDecimal(b.right()));
            default -> false;
        };
    }

    private String decimalOperand(Expr e) {
        return isDecimal(e) ? emitExpr(e) : "Decimal.valueOf(" + emitExpr(e) + ")";
    }

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
                default -> left + ".divide(" + right + ", 8, java.math.RoundingMode.HALF_UP)";
            };
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
        return "new " + mapType(nw.type()) + "(" + emitArgs(nw.args()) + ")";
    }

    private String emitArgs(List<Expr> args) {
        List<String> out = new ArrayList<>();
        for (Expr a : args) out.add(emitExpr(a));
        return String.join(", ", out);
    }

    private String emitSObject(SObjectLit so) {
        String base = base(so.type());
        if (isTyped(base)) {
            // typed: new Account(Name=x) -> new Account(){{ setName(x); }} (setters type-check)
            StringBuilder sb = new StringBuilder("new ").append(base).append("(){{");
            for (FieldInit f : so.fields()) {
                sb.append(" set").append(schema.canonicalField(base, f.name())).append('(')
                  .append(emitExpr(f.value())).append(");");
            }
            return sb.append(" }}").toString();
        }
        StringBuilder sb = new StringBuilder("new SObject(\"").append(base).append('"');
        for (FieldInit f : so.fields()) {
            sb.append(", \"").append(f.name()).append("\", ").append(emitExpr(f.value()));
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
        if (SCHEMA_TYPES.contains(base)) return base; // Schema.SObjectType -> runtime SObjectType
        if (RUNTIME_TYPES.contains(base)) return base; // Pattern / Matcher / LoggingLevel / Limits
        if (JAVA_SAME.contains(base)) return base;
        if (base.equals("Decimal") || base.equals("Date")
            || base.equals("Datetime") || base.equals("Time")) return base; // runtime types
        if (base.equalsIgnoreCase("Blob")) return "Object"; // no runtime yet
        if (base.equals("Id")) return "String";
        if (COLLECTIONS.contains(base)) {
            if (lt < 0) {
                return base;
            }
            String generics = mapGenerics(t.substring(lt));
            // Apex List<Object>/Set<Object> accept any List/Set (covariant to Object); Java is
            // invariant, so emit the raw type to mirror Apex's permissiveness (a List<String>
            // can then be passed where a List<Object> is expected, as in Apex)
            return generics.equals("<Object>") ? base : base + generics;
        }
        if (innerTypes.contains(base)) { // nested class: emit by its simple Java name
            return base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
        }
        if (userClasses.contains(base)) return base;
        if (typedSObjects.contains(base)) return base; // generated typed sObject class
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

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
