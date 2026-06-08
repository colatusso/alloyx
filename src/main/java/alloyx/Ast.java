package alloyx;

import java.util.List;

// AST for the JVM port. Sealed interfaces + records => exhaustive switches in
// the transpiler. The AST is the stable contract (same role as in the Python
// prototype); the front-end could later be swapped for ANTLR without touching it.

// --- declarations
record ClassDecl(String name, List<MethodDecl> methods, List<Field> fields, String superclass) {}

record Field(String type, String name, Expr init, boolean isStatic) {}

record Param(String type, String name) {}

record MethodDecl(String name, boolean isStatic, String returnType,
                  List<Param> params, List<Stmt> body, List<String> annotations, int line) {
    boolean isTest() {
        return annotations.contains("istest");
    }
}

// --- statements
sealed interface Stmt permits VarDecl, Assign, ExprStmt, Return, If, While, ForEach, For, Dml, Try, Throw, GuardedBlock {}

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
    permits Num, DecimalLit, Str, Bool, Null, Name, Unary, Binary, Ternary, Call, New, SObjectLit,
            Soql, Index, ListLit, MapLit, Prop, MethodCall, Cast, InstanceOf, ClassLit {}

record Num(int value) implements Expr {}

// a decimal literal like 19.90 -> Decimal.valueOf("19.90")
record DecimalLit(String value) implements Expr {}

record Str(String value) implements Expr {}

record Bool(boolean value) implements Expr {}

record Null() implements Expr {}

record Name(String ident) implements Expr {}

record Unary(String op, Expr operand) implements Expr {}

record Binary(String op, Expr left, Expr right) implements Expr {}

record Ternary(Expr cond, Expr then, Expr els) implements Expr {}

record Call(String callee, List<Expr> args) implements Expr {}

record New(String type, List<Expr> args) implements Expr {}

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
record Prop(Expr target, String name) implements Expr {}

record MethodCall(Expr target, String name, List<Expr> args) implements Expr {}

record Cast(String type, Expr expr) implements Expr {}

record InstanceOf(Expr expr, String type) implements Expr {}

// a type literal: Foo.class / List<Account>.class
record ClassLit(String type) implements Expr {}
