// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects every type name referenced by a set of classes — from type positions
 * (fields, params, returns, locals, new/cast/instanceof, collection generics) and
 * from SOQL {@code FROM} clauses. The Workspace filters these down to the ones the
 * schema can describe, which become generated typed sObject classes.
 *
 * Over-collection is fine: non-sObject names (List, Integer, user classes) are
 * dropped by the Workspace before any describe is attempted.
 */
final class SObjectScan {
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SOQL_FROM = Pattern.compile("(?i)\\bFROM\\s+([A-Za-z_][A-Za-z0-9_]*)");

    static Set<String> referenced(Collection<ClassDecl> decls) {
        Set<String> out = new HashSet<>();
        for (ClassDecl c : decls) {
            walkClass(c, out);
        }
        return out;
    }

    // Collect sObject references from a class AND its nested classes — an sObject named only inside
    // an inner method (e.g. a wrapper's toSObject building a typed sObject literal) must still be
    // discovered for describe/typing, exactly as if it appeared in the outer class.
    private static void walkClass(ClassDecl c, Set<String> out) {
        addType(c.superclass(), out);
        for (Field f : c.fields()) {
            addType(f.type(), out);
            walkExpr(f.init(), out);
        }
        for (MethodDecl m : c.methods()) {
            // a constructor has no return type — the parser leaves the modifier
            // (e.g. "public") in that slot, which is not a type to collect
            if (!m.name().equals(c.name())) {
                addType(m.returnType(), out);
            }
            for (Param p : m.params()) {
                addType(p.type(), out);
            }
            walkBody(m.body(), out);
        }
        if (c.inners() != null) {
            for (ClassDecl inner : c.inners()) {
                walkClass(inner, out);
            }
        }
    }

    // pull every identifier out of a type string so "Map<Id, Account>" contributes
    // Map, Id and Account (the Workspace keeps only the ones that describe).
    private static void addType(String type, Set<String> out) {
        if (type == null) {
            return;
        }
        Matcher m = IDENT.matcher(type);
        while (m.find()) {
            out.add(m.group());
        }
    }

    private static void walkBody(List<Stmt> body, Set<String> out) {
        if (body == null) {
            return;
        }
        for (Stmt s : body) {
            walkStmt(s, out);
        }
    }

    private static void walkArgs(List<Expr> exprs, Set<String> out) {
        if (exprs == null) {
            return;
        }
        for (Expr e : exprs) {
            walkExpr(e, out);
        }
    }

    private static void walkStmt(Stmt s, Set<String> out) {
        if (s == null) {
            return;
        }
        switch (s) {
            case VarDecl v -> {
                addType(v.type(), out);
                walkExpr(v.init(), out);
            }
            case Assign a -> {
                walkExpr(a.target(), out);
                walkExpr(a.value(), out);
            }
            case ExprStmt e -> walkExpr(e.expr(), out);
            case Return r -> walkExpr(r.value(), out);
            case If iff -> {
                walkExpr(iff.cond(), out);
                walkBody(iff.thenBody(), out);
                walkBody(iff.elseBody(), out);
            }
            case While w -> {
                walkExpr(w.cond(), out);
                walkBody(w.body(), out);
            }
            case ForEach fe -> {
                addType(fe.type(), out);
                walkExpr(fe.iterable(), out);
                walkBody(fe.body(), out);
            }
            case For f -> {
                walkStmt(f.init(), out);
                walkExpr(f.cond(), out);
                walkStmt(f.update(), out);
                walkBody(f.body(), out);
            }
            case Dml d -> walkExpr(d.value(), out);
            case Try t -> {
                walkBody(t.body(), out);
                for (Catch c : t.catches()) {
                    addType(c.type(), out);
                    walkBody(c.body(), out);
                }
                walkBody(t.finallyBody(), out);
            }
            case Throw th -> walkExpr(th.value(), out);
            case GuardedBlock g -> {
                walkExpr(g.guard(), out);
                walkBody(g.body(), out);
            }
            case Group grp -> walkBody(grp.stmts(), out);
            case SwitchStmt sw -> {
                walkExpr(sw.subject(), out);
                for (WhenCase c : sw.cases()) {
                    for (Expr v : c.values()) walkExpr(v, out);
                    walkBody(c.body(), out);
                }
                walkBody(sw.elseBody(), out);
            }
        }
    }

    private static void walkExpr(Expr e, Set<String> out) {
        if (e == null) {
            return;
        }
        switch (e) {
            case Unary u -> walkExpr(u.operand(), out);
            case Binary b -> {
                walkExpr(b.left(), out);
                walkExpr(b.right(), out);
            }
            case Ternary t -> {
                walkExpr(t.cond(), out);
                walkExpr(t.then(), out);
                walkExpr(t.els(), out);
            }
            case Call c -> walkArgs(c.args(), out);
            case New nw -> {
                addType(nw.type(), out);
                walkArgs(nw.args(), out);
            }
            case SObjectLit so -> {
                addType(so.type(), out);
                for (FieldInit fi : so.fields()) {
                    walkExpr(fi.value(), out);
                }
            }
            case Soql q -> {
                Matcher m = SOQL_FROM.matcher(q.query());
                while (m.find()) {
                    out.add(m.group(1));
                }
                for (Bind b : q.binds()) {
                    walkExpr(b.value(), out);
                }
            }
            case Index ix -> {
                walkExpr(ix.target(), out);
                walkExpr(ix.index(), out);
            }
            case ListLit l -> {
                addType(l.type(), out);
                walkArgs(l.elements(), out);
            }
            case MapLit mp -> {
                addType(mp.type(), out);
                walkArgs(mp.keys(), out);
                walkArgs(mp.values(), out);
            }
            case Prop p -> {
                // a bare-Name target of a member access can be a STATIC field-token reference on an
                // sObject TYPE (the selector pattern Item__c.Id -> Schema.SObjectField). Collect the
                // name as a candidate sObject so the Workspace generates its typed class (which carries
                // the static tokens). Over-collection is fine: a non-describable name is dropped.
                if (p.target() instanceof Name n
                        && !n.ident().equals("this") && !n.ident().equals("super")) {
                    out.add(n.ident());
                }
                walkExpr(p.target(), out);
            }
            case MethodCall mc -> {
                walkExpr(mc.target(), out);
                walkArgs(mc.args(), out);
            }
            case Cast c -> {
                addType(c.type(), out);
                walkExpr(c.expr(), out);
            }
            case InstanceOf io -> {
                walkExpr(io.expr(), out);
                addType(io.type(), out);
            }
            case ClassLit cl -> addType(cl.type(), out);
            default -> {
                // leaves: Num, DecimalLit, Str, Bool, Null, Name — no nested types
            }
        }
    }

    private SObjectScan() {
    }
}
