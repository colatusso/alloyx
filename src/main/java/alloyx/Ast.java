package alloyx;

import java.util.List;

// AST for the JVM port. Sealed interfaces + records => exhaustive switches in
// the transpiler. The AST is the stable contract (same role as in the Python
// prototype); the front-end could later be swapped for ANTLR without touching it.

// --- declarations
// A type declaration. `kind` is "class", "interface" or "enum". `inners` are nested
// types; `interfaces` the implemented/extended interface names; `enumValues` the
// constants (only for an enum). Defaults keep plain-class construction unchanged.
record ClassDecl(String name, List<MethodDecl> methods, List<Field> fields,
                 String superclass, List<ClassDecl> inners, List<String> interfaces,
                 String kind, List<String> enumValues) {
    ClassDecl(String name, List<MethodDecl> methods, List<Field> fields, String superclass) {
        this(name, methods, fields, superclass, List.of(), List.of(), "class", List.of());
    }

    ClassDecl(String name, List<MethodDecl> methods, List<Field> fields, String superclass,
              List<ClassDecl> inners) {
        this(name, methods, fields, superclass, inners, List.of(), "class", List.of());
    }
}

record Field(String type, String name, Expr init, boolean isStatic) {}

record Param(String type, String name) {}

record MethodDecl(String name, boolean isStatic, String returnType,
                  List<Param> params, List<Stmt> body, List<String> annotations, int line) {
    boolean isTest() {
        return annotations.contains("istest");
    }
}

// --- statements
sealed interface Stmt permits VarDecl, Assign, ExprStmt, Return, If, While, ForEach, For, Dml, Try, Throw, GuardedBlock, Group {}

// several statements emitted inline, sharing the enclosing scope (no braces) —
// e.g. a multi-variable declaration: Integer a = 1, b, c = 3;
record Group(List<Stmt> stmts) implements Stmt {}

record VarDecl(String type, String name, Expr init) implements Stmt {}

record Assign(Expr target, Expr value) implements Stmt {}

record ExprStmt(Expr expr) implements Stmt {}

record Return(Expr value) implements Stmt {}

record If(Expr cond, List<Stmt> thenBody, List<Stmt> elseBody) implements Stmt {}

record While(Expr cond, List<Stmt> body) implements Stmt {}

record ForEach(String type, String name, Expr iterable, List<Stmt> body) implements Stmt {}

// classic for: any of init/cond/update may be null
record For(Stmt init, Expr cond, Stmt update, List<Stmt> body) implements Stmt {}

record Dml(String op, Expr value) implements Stmt {}

record Try(List<Stmt> body, List<Catch> catches, List<Stmt> finallyBody) implements Stmt {}

record Catch(String type, String name, List<Stmt> body) {}

record Throw(Expr value) implements Stmt {}

// a bare { } block or a guarded block (e.g. System.runAs(u){...}); the guard,
// if any, has no local equivalent and is dropped — only the body runs
record GuardedBlock(Expr guard, List<Stmt> body) implements Stmt {}

// --- expressions
sealed interface Expr
    permits Num, DecimalLit, Str, Bool, Null, Name, Unary, Postfix, Binary, Ternary, Call, New,
            ArrayNew, SObjectLit, Soql, Index, ListLit, MapLit, Prop, MethodCall, Cast,
            InstanceOf, ClassLit {}

record Num(int value) implements Expr {}

// a decimal literal like 19.90 -> Decimal.valueOf("19.90")
record DecimalLit(String value) implements Expr {}

record Str(String value) implements Expr {}

record Bool(boolean value) implements Expr {}

record Null() implements Expr {}

record Name(String ident) implements Expr {}

record Unary(String op, Expr operand) implements Expr {}

// post-increment/decrement used as an expression: x++ in get(i++), arr[j++], etc.
record Postfix(Expr operand, String op) implements Expr {}

record Binary(String op, Expr left, Expr right) implements Expr {}

record Ternary(Expr cond, Expr then, Expr els) implements Expr {}

record Call(String callee, List<Expr> args) implements Expr {}

record New(String type, List<Expr> args) implements Expr {}

// Apex `new T[n]`: a List<T> pre-sized with n null elements
record ArrayNew(String elementType, Expr size) implements Expr {}

record SObjectLit(String type, List<FieldInit> fields) implements Expr {}

record FieldInit(String name, Expr value) {}

record Soql(String query, List<Bind> binds) implements Expr {}

record Bind(String key, Expr value) {}

record Index(Expr target, Expr index) implements Expr {}

// collection literals: new List<X>{a,b}, new Set<X>{a,b}, new Map<K,V>{k => v}
record ListLit(String type, List<Expr> elements) implements Expr {}

record MapLit(String type, List<Expr> keys, List<Expr> values) implements Expr {}

// member access (expr.name) and method call on an expression (expr.name(args)),
// so chains like foo().bar().baz parse to the left as nested targets
// `safe` marks Apex safe navigation: a?.b yields null instead of dereferencing null.
record Prop(Expr target, String name, boolean safe) implements Expr {
    Prop(Expr target, String name) {
        this(target, name, false);
    }
}

record MethodCall(Expr target, String name, List<Expr> args, boolean safe) implements Expr {
    MethodCall(Expr target, String name, List<Expr> args) {
        this(target, name, args, false);
    }
}

record Cast(String type, Expr expr) implements Expr {}

record InstanceOf(Expr expr, String type) implements Expr {}

// a type literal: Foo.class / List<Account>.class
record ClassLit(String type) implements Expr {}
