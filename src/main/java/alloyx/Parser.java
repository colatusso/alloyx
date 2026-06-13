// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import alloyx.Lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser (mirrors the Python prototype). Covers classes,
 * fields, methods (params/return/annotations), control flow, expressions with
 * precedence, SOQL [ ... ], sObject literals (new X(Field=v)), and DML.
 * Nothing is hardcoded to user names — keywords/modifiers/types are discovered
 * structurally.
 */
final class Parser {
    private static final Set<String> DML_OPS =
        Set.of("insert", "update", "delete", "upsert", "undelete");

    private final List<Token> toks;
    private final String src;
    private int i = 0;
    // statement -> originating Apex line, for the source map (used by `allx check`)
    private final java.util.Map<Stmt, Integer> stmtLines = new java.util.IdentityHashMap<>();

    private Parser(List<Token> toks, String src) {
        this.toks = toks;
        this.src = src;
    }

    static ClassDecl parse(String src) {
        return new Parser(Lexer.tokenize(src), src).parseClass();
    }

    // parse plus the statement->Apex-line map, so a javac error on the generated
    // Java can be reported against the right line of the original .cls
    record Parsed(ClassDecl cls, java.util.Map<Stmt, Integer> stmtLines) {}

    static Parsed parseWithLines(String src) {
        Parser p = new Parser(Lexer.tokenize(src), src);
        ClassDecl cls = p.parseClass();
        return new Parsed(cls, p.stmtLines);
    }

    private Token peek() {
        return toks.get(i);
    }

    private Token peek(int k) {
        return toks.get(i + k);
    }

    private Token advance() {
        return toks.get(i++);
    }

    // Apex is case-insensitive: keyword/punctuation comparisons ignore case. This is
    // the single normalization point — `at`/`accept`/`expect` and the few direct value
    // comparisons funnel through equalsIgnoreCase. Identifier TEXT is never touched: it
    // always reaches the AST via advance().value(), preserving the source's original case.
    private boolean at(String value) {
        return peek().value().equalsIgnoreCase(value);
    }

    private boolean isIdent() {
        return peek().kind().equals("IDENT");
    }

    // could this token begin a unary operand right after a cast's closing ')'
    private boolean startsOperand(Token t) {
        if (t.kind().equals("NUMBER") || t.kind().equals("STRING")) return true;
        if (t.value().equals("(") || t.value().equalsIgnoreCase("new")) return true;
        return t.kind().equals("IDENT") && !t.value().equalsIgnoreCase("instanceof");
    }

    private boolean accept(String value) {
        if (at(value)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(String value) {
        Token t = advance();
        if (!t.value().equalsIgnoreCase(value)) {
            throw new RuntimeException(
                "expected '" + value + "' but got '" + t.value() + "' (" + lineOf(t) + ")");
        }
        return t;
    }

    private int lineNum(int offset) {
        int line = 1;
        int end = Math.min(offset, src.length());
        for (int k = 0; k < end; k++) {
            if (src.charAt(k) == '\n') line++;
        }
        return line;
    }

    private String lineOf(Token t) {
        return "line " + lineNum(t.start());
    }

    // --- types (consume an Apex type incl. generics/arrays as a raw string)
    private String consumeType() {
        if (!isIdent()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(advance().value());
        while (at(".") && peek(1).kind().equals("IDENT")) {
            advance();
            sb.append('.').append(advance().value());
        }
        if (at("<")) {
            sb.append(consumeGeneric());
        }
        if (at("[") && peek(1).value().equals("]")) {
            advance();
            advance();
            sb.append("[]");
        }
        return sb.toString();
    }

    private String consumeGeneric() {
        StringBuilder sb = new StringBuilder(advance().value()); // '<'
        int depth = 1;
        while (depth > 0 && !peek().kind().equals("EOF")) {
            Token t = advance();
            sb.append(t.value());
            if (t.value().equals("<")) depth++;
            else if (t.value().equals(">")) depth--;
        }
        return sb.toString();
    }

    private List<String> parseAnnotations() {
        List<String> anns = new ArrayList<>();
        while (at("@")) {
            advance();
            anns.add(advance().value().toLowerCase());
            if (at("(")) {
                int depth = 0;
                do {
                    Token t = advance();
                    if (t.value().equals("(")) depth++;
                    else if (t.value().equals(")")) depth--;
                    else if (t.kind().equals("EOF")) break;
                } while (depth > 0);
            }
        }
        return anns;
    }

    // --- class / interface / enum
    private ClassDecl parseClass() {
        parseAnnotations();
        boolean isAbstract = false;
        while (isIdent() && !at("class") && !at("interface") && !at("enum")) {
            if (peek().value().equalsIgnoreCase("abstract")) isAbstract = true;
            advance(); // skip modifiers (capturing `abstract` as we go)
        }
        String kind = at("interface") ? "interface" : at("enum") ? "enum" : "class";
        advance(); // consume the class | interface | enum keyword
        String name = advance().value();
        if (kind.equals("enum")) {
            return parseEnumBody(name);
        }
        String superclass = null;
        List<String> interfaces = new ArrayList<>();
        if (accept("extends")) {
            superclass = consumeType();
            while (accept(",")) interfaces.add(base(consumeType())); // interface extends J, K
        }
        if (accept("implements")) {
            interfaces.add(base(consumeType()));
            while (accept(",")) interfaces.add(base(consumeType()));
        }
        return parseClassBody(name, superclass, interfaces, kind, isAbstract);
    }

    // An Apex enum is a flat list of constant names: enum E { A, B, C }
    private ClassDecl parseEnumBody(String name) {
        expect("{");
        List<String> values = new ArrayList<>();
        while (!at("}") && !peek().kind().equals("EOF")) {
            String v = advance().value();
            if (!v.equals(",")) values.add(v);
        }
        expect("}");
        return new ClassDecl(name, List.of(), List.of(), null, List.of(),
            List.of(), "enum", values);
    }

    // Parse a class/interface body from the opening '{' onward. Shared by the top-level
    // type and inner types (which arrive here past their `class Name extends ...`).
    private ClassDecl parseClassBody(String name, String superclass,
                                     List<String> interfaces, String kind) {
        return parseClassBody(name, superclass, interfaces, kind, false);
    }

    private ClassDecl parseClassBody(String name, String superclass,
                                     List<String> interfaces, String kind, boolean isAbstract) {
        expect("{");
        List<MethodDecl> methods = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        List<ClassDecl> inners = new ArrayList<>();
        while (!at("}")) {
            Object member = parseMember(name);
            if (member == null) continue; // tolerated static{} block
            if (member instanceof Field f) fields.add(f);
            else if (member instanceof ClassDecl inner) inners.add(inner); // nested type
            else if (member instanceof List<?> group) {
                for (Object g : group) fields.add((Field) g); // several fields on one line
            } else methods.add((MethodDecl) member);
        }
        expect("}");
        return new ClassDecl(name, methods, fields, superclass, inners, interfaces, kind,
            List.of(), isAbstract);
    }

    // strip any generic suffix: List<String> -> List
    private static String base(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt) : type;
    }

    // first position of a keyword among captured member words, case-insensitively
    // (Apex modifiers/keywords may be written Static, PUBLIC, Class, …). Compares only;
    // the words themselves keep their original case for the AST.
    private static int kwIndex(List<String> words, String keyword) {
        for (int k = 0; k < words.size(); k++) {
            if (words.get(k).equalsIgnoreCase(keyword)) return k;
        }
        return -1;
    }

    private Object parseMember(String className) {
        List<String> anns = parseAnnotations();
        int memberStart = peek().start();
        List<String> parts = new ArrayList<>();
        while (isIdent()) {
            StringBuilder word = new StringBuilder(advance().value());
            while (at(".") && peek(1).kind().equals("IDENT")) {
                advance();
                word.append('.').append(advance().value()); // qualified type: Schema.SObjectField
            }
            if (at("<")) word.append(consumeGeneric());
            if (at("[") && peek(1).value().equals("]")) {
                advance();
                advance();
                word.append("[]");
            }
            parts.add(word.toString());
        }
        String name = parts.get(parts.size() - 1);
        boolean isStatic = kwIndex(parts.subList(0, parts.size() - 1), "static") >= 0;
        boolean isAbstract = kwIndex(parts.subList(0, parts.size() - 1), "abstract") >= 0;
        String returnType = parts.size() >= 2 ? parts.get(parts.size() - 2) : "void";

        if (at("(")) { // method or constructor
            advance();
            List<Param> params = new ArrayList<>();
            if (!at(")")) {
                params.add(parseParam());
                while (accept(",")) params.add(parseParam());
            }
            expect(")");
            List<Stmt> body;
            if (at("{")) {
                body = parseBlock();
            } else {
                expect(";"); // abstract / interface signature
                body = new ArrayList<>();
            }
            return new MethodDecl(name, isStatic, returnType, params, body, anns,
                lineNum(memberStart), isAbstract);
        }
        if (at("{")) {
            // a nested type is parsed (not discarded): the cursor is at its body's '{', and
            // the keyword/name were captured among `parts` (... class|interface Name [extends
            // Super] [implements ...]). An inner enum reads its constant list separately.
            int enumAt = kwIndex(parts, "enum");
            if (enumAt >= 0) {
                return parseEnumBody(parts.get(enumAt + 1));
            }
            int ifaceAt = kwIndex(parts, "interface");
            int classAt = kwIndex(parts, "class");
            if (classAt >= 0 || ifaceAt >= 0) {
                String kw = ifaceAt >= 0 ? "interface" : "class";
                String innerName = parts.get((ifaceAt >= 0 ? ifaceAt : classAt) + 1);
                int extAt = kwIndex(parts, "extends");
                String sup = extAt >= 0 ? parts.get(extAt + 1) : null;
                // capture the inner type's interfaces too (was dropped, so an inner
                // `class X implements Y` lost the `implements Y` and didn't satisfy Y).
                // modifiers/keyword/name/extends/implements all arrived in `parts`.
                List<String> innerInterfaces = new ArrayList<>();
                int implAt = kwIndex(parts, "implements");
                if (implAt >= 0) {
                    for (int k = implAt + 1; k < parts.size(); k++) {
                        innerInterfaces.add(base(parts.get(k)));
                    }
                }
                boolean innerAbstract = kwIndex(parts, "abstract") >= 0;
                return parseClassBody(innerName, sup, innerInterfaces, kw, innerAbstract);
            }
            // property (Type name { get; set; }) or a static{} initializer block
            skipBalancedBraces();
            accept(";"); // tolerate a trailing ';'
            if (parts.size() < 2) {
                return null; // static initializer — not emitted
            }
            // auto-property modelled as a plain field (get/set body, if any, is dropped)
            return new Field(parts.get(parts.size() - 2), name, null, isStatic);
        }
        // field — Apex allows several on one line: Type a = x, b, c = z;
        String fieldType = parts.size() >= 2 ? parts.get(parts.size() - 2) : "Object";
        Expr init = accept("=") ? parseExpr() : null;
        if (!at(",")) {
            expect(";");
            return new Field(fieldType, name, init, isStatic);
        }
        List<Field> group = new ArrayList<>();
        group.add(new Field(fieldType, name, init, isStatic));
        while (accept(",")) {
            String fname = advance().value(); // next field shares the type
            Expr finit = accept("=") ? parseExpr() : null;
            group.add(new Field(fieldType, fname, finit, isStatic));
        }
        expect(";");
        return group;
    }

    private void skipBalancedBraces() {
        expect("{");
        int depth = 1;
        while (depth > 0 && !peek().kind().equals("EOF")) {
            String v = advance().value();
            if (v.equals("{")) depth++;
            else if (v.equals("}")) depth--;
        }
    }

    private Param parseParam() {
        String type = consumeType();
        String name = advance().value();
        return new Param(type, name);
    }

    // --- statements
    private List<Stmt> parseBlock() {
        expect("{");
        List<Stmt> stmts = new ArrayList<>();
        while (!at("}")) stmts.add(parseStmt());
        expect("}");
        return stmts;
    }

    private List<Stmt> parseBlockOrStmt() {
        if (at("{")) return parseBlock();
        List<Stmt> one = new ArrayList<>();
        one.add(parseStmt());
        return one;
    }

    private Stmt parseStmt() {
        int line = lineNum(peek().start());
        Stmt s = parseStmtInner();
        stmtLines.put(s, line); // remember where this statement came from in the .cls
        return s;
    }

    private Stmt parseStmtInner() {
        // case-insensitive keyword dispatch: Apex `RETURN`, `IF`, `Insert` are keywords.
        // The DML op is emitted lowercased (Database.insert) since the runtime methods are.
        String kw = peek().value().toLowerCase(java.util.Locale.ROOT);
        if (kw.equals("{")) return new GuardedBlock(null, parseBlock());
        if (kw.equals("return")) {
            advance();
            if (accept(";")) return new Return(null);
            Expr v = parseExpr();
            expect(";");
            return new Return(v);
        }
        if (kw.equals("if")) return parseIf();
        if (kw.equals("while")) return parseWhile();
        if (kw.equals("for")) return parseFor();
        if (kw.equals("switch")) return parseSwitch();
        if (kw.equals("try")) return parseTry();
        if (kw.equals("throw")) {
            advance();
            Expr v = parseExpr();
            expect(";");
            return new Throw(v);
        }
        if (DML_OPS.contains(kw)) {
            advance();
            Expr v = parseExpr();
            while (!at(";") && !peek().kind().equals("EOF")) advance(); // tolerate extra (e.g. upsert field)
            expect(";");
            return new Dml(kw, v);
        }
        // local var decl?  TYPE NAME ...
        Stmt decl = tryParseLocalDecl();
        if (decl != null) return decl;
        // assignment / expression statement
        Expr lvalue = parseExpr();
        String op = peek().value();
        if (op.equals("=")) {
            advance();
            Expr value = parseExpr();
            expect(";");
            return new Assign(lvalue, value);
        }
        if (op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=")) {
            advance();
            Expr rhs = parseExpr();
            expect(";");
            return new Assign(lvalue, new Binary(op.substring(0, 1), lvalue, rhs));
        }
        if (op.equals("++") || op.equals("--")) {
            advance();
            expect(";");
            return new Assign(lvalue, new Binary(op.substring(0, 1), lvalue, new Num(1)));
        }
        if (at("{")) return new GuardedBlock(lvalue, parseBlock()); // e.g. System.runAs(u){...}
        expect(";");
        return new ExprStmt(lvalue);
    }

    private Stmt tryParseLocalDecl() {
        int save = i;
        // optional local modifiers in any order (final / transient): no Java equivalent, dropped
        while (accept("final") || accept("transient")) { /* skip */ }
        String type = consumeType();
        if (type != null && isIdent()) {
            String name = advance().value();
            Expr init = accept("=") ? parseExpr() : null;
            // Apex allows several locals on one line: Type a = 1, b, c = 3;
            List<Stmt> decls = new ArrayList<>();
            decls.add(new VarDecl(type, name, init));
            while (accept(",")) {
                String n = advance().value();
                Expr ini = accept("=") ? parseExpr() : null;
                decls.add(new VarDecl(type, n, ini));
            }
            if (accept(";")) {
                return decls.size() == 1 ? decls.get(0) : new Group(decls);
            }
        }
        i = save;
        return null;
    }

    private Stmt parseIf() {
        expect("if");
        expect("(");
        Expr cond = parseExpr();
        expect(")");
        List<Stmt> thenBody = parseBlockOrStmt();
        List<Stmt> elseBody = accept("else") ? parseBlockOrStmt() : new ArrayList<>();
        return new If(cond, thenBody, elseBody);
    }

    private Stmt parseTry() {
        expect("try");
        List<Stmt> body = parseBlock();
        List<Catch> catches = new ArrayList<>();
        while (at("catch")) {
            advance();
            expect("(");
            String type = consumeType();
            String name = advance().value();
            expect(")");
            catches.add(new Catch(type, name, parseBlock()));
        }
        List<Stmt> finallyBody = accept("finally") ? parseBlock() : new ArrayList<>();
        return new Try(body, catches, finallyBody);
    }

    private Stmt parseWhile() {
        expect("while");
        expect("(");
        Expr cond = parseExpr();
        expect(")");
        return new While(cond, parseBlockOrStmt());
    }

    private Stmt parseFor() {
        expect("for");
        expect("(");
        // for-each:  for (Type name : iterable)
        int save = i;
        String type = consumeType();
        if (type != null && isIdent()) {
            String name = advance().value();
            if (accept(":")) {
                Expr iterable = parseExpr();
                expect(")");
                return new ForEach(type, name, iterable, parseBlockOrStmt());
            }
        }
        // classic:  for (init; cond; update)
        i = save;
        Stmt init = at(";") ? null : parseForClause();
        expect(";");
        Expr cond = at(";") ? null : parseExpr();
        expect(";");
        Stmt update = at(")") ? null : parseForClause();
        expect(")");
        return new For(init, cond, update, parseBlockOrStmt());
    }

    // one init/update clause of a classic for, parsed WITHOUT a trailing ';'
    private Stmt parseForClause() {
        int save = i;
        while (accept("final") || accept("transient")) { /* skip local modifiers */ }
        String type = consumeType();
        if (type != null && isIdent()) {
            String name = advance().value();
            Expr init = accept("=") ? parseExpr() : null;
            // Apex allows several declarators in the for-init: for (Integer i = 0, len = n; ...)
            // — collect them into a Group, the same multi-declaration node a plain statement uses,
            // so all names get block-scoped and emitted (see emitForClause / For scope binding).
            if (!at(",")) {
                return new VarDecl(type, name, init);
            }
            List<Stmt> decls = new ArrayList<>();
            decls.add(new VarDecl(type, name, init));
            while (accept(",")) {
                String n = advance().value(); // shares the leading type
                Expr ini = accept("=") ? parseExpr() : null;
                decls.add(new VarDecl(type, n, ini));
            }
            return new Group(decls);
        }
        i = save;
        Expr lvalue = parseExpr();
        String op = peek().value();
        if (op.equals("=")) {
            advance();
            return new Assign(lvalue, parseExpr());
        }
        if (op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=")) {
            advance();
            return new Assign(lvalue, new Binary(op.substring(0, 1), lvalue, parseExpr()));
        }
        if (op.equals("++") || op.equals("--")) {
            advance();
            return new Assign(lvalue, new Binary(op.substring(0, 1), lvalue, new Num(1)));
        }
        return new ExprStmt(lvalue);
    }

    // Apex `switch on <subject> { when <vals> { ... } ... when else { ... } }`. `on`/`when`/`else`
    // are CONTEXTUAL keywords (plain IDENTs here). Case values are literal lists (Integer/String)
    // and trivial single idents (enum constants); a type-pattern `when Account a` is NOT in scope —
    // it raises a clear error rather than being half-parsed. `when null` is allowed (a value).
    private Stmt parseSwitch() {
        expect("switch");
        expect("on");
        Expr subject = parseExpr();
        expect("{");
        List<WhenCase> cases = new ArrayList<>();
        List<Stmt> elseBody = new ArrayList<>();
        while (at("when")) {
            advance(); // 'when'
            if (at("else")) {
                advance();
                elseBody = parseBlock();
                continue;
            }
            List<Expr> values = new ArrayList<>();
            values.add(parseWhenValue());
            while (accept(",")) values.add(parseWhenValue());
            cases.add(new WhenCase(values, parseBlock()));
        }
        expect("}");
        return new SwitchStmt(subject, cases, elseBody);
    }

    // One `when` match value: a literal (number/string/true/false/null) or a single bare ident
    // (an enum constant). A TYPE-pattern `when Account a` (two idents before the block) is rejected
    // with a clear error — the corpus never uses it, so half-implementing it would be worse.
    private Expr parseWhenValue() {
        if (isIdent() && peek(1).kind().equals("IDENT")) {
            Token t = peek();
            throw new RuntimeException(
                "switch type-pattern (when <Type> <name>) is not supported (" + lineOf(t) + ")");
        }
        return parseExpr();
    }

    // --- expressions (precedence low -> high)
    private Expr parseExpr() {
        return parseNullCoalesce();
    }

    // Apex ?? (null-coalescing): the lowest-precedence binary, RIGHT-associative, so
    // `a ?? b ?? c` parses as `a ?? (b ?? c)`. Modeled as a Binary("??", ...) the typer/
    // emitter recognize. The recursive right operand (parseNullCoalesce) gives right-assoc.
    private Expr parseNullCoalesce() {
        Expr left = parseTernary();
        if (at("??")) {
            advance();
            return new Binary("??", left, parseNullCoalesce());
        }
        return left;
    }

    private Expr parseTernary() {
        Expr cond = parseOr();
        if (accept("?")) {
            Expr then = parseExpr();
            expect(":");
            return new Ternary(cond, then, parseExpr());
        }
        return cond;
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (at("||")) {
            advance();
            left = new Binary("||", left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseBitOr();
        while (at("&&")) {
            advance();
            left = new Binary("&&", left, parseBitOr());
        }
        return left;
    }

    private Expr parseBitOr() {
        Expr left = parseBitXor();
        while (at("|")) {
            advance();
            left = new Binary("|", left, parseBitXor());
        }
        return left;
    }

    private Expr parseBitXor() {
        Expr left = parseBitAnd();
        while (at("^")) {
            advance();
            left = new Binary("^", left, parseBitAnd());
        }
        return left;
    }

    private Expr parseBitAnd() {
        Expr left = parseEquality();
        while (at("&")) {
            advance();
            left = new Binary("&", left, parseEquality());
        }
        return left;
    }

    private Expr parseEquality() {
        Expr left = parseComparison();
        // ==, != : value equality. ===, !== : Apex identity (reference) comparison — kept as a
        // distinct Binary op so emission bypasses the Objects.equals value helper. <> is Apex's
        // legacy inequality, an exact synonym of != (normalized here so emission needs no new case).
        while (at("==") || at("!=") || at("===") || at("!==") || at("<>")) {
            String op = advance().value();
            if (op.equals("<>")) op = "!=";
            left = new Binary(op, left, parseComparison());
        }
        return left;
    }

    private Expr parseComparison() {
        Expr left = parseShift();
        while (true) {
            if (peek().value().equalsIgnoreCase("instanceof")) {
                advance();
                left = new InstanceOf(left, consumeType());
            } else if (at("<") || at(">") || at("<=") || at(">=")) {
                String op = advance().value();
                left = new Binary(op, left, parseShift());
            } else {
                break;
            }
        }
        return left;
    }

    // Apex << / >> aren't single tokens (that would break nested generics in the
    // lexer); recognize them here as two adjacent < or > tokens.
    private Expr parseShift() {
        Expr left = parseAdd();
        while ((at("<") && peek(1).value().equals("<"))
            || (at(">") && peek(1).value().equals(">"))) {
            String op = advance().value() + advance().value();
            left = new Binary(op, left, parseAdd());
        }
        return left;
    }

    private Expr parseAdd() {
        Expr left = parseMul();
        while (at("+") || at("-")) {
            String op = advance().value();
            left = new Binary(op, left, parseMul());
        }
        return left;
    }

    private Expr parseMul() {
        Expr left = parseUnary();
        while (at("*") || at("/")) {
            String op = advance().value();
            left = new Binary(op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (at("!") || at("-") || at("+") || at("~") || at("++") || at("--")) {
            String op = advance().value();
            return new Unary(op, parseUnary());
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr expr = parsePrimary();
        while (at("[") || at(".") || (at("?") && peek(1).value().equals("."))) {
            if (accept("[")) {
                Expr index = parseExpr();
                expect("]");
                expr = new Index(expr, index);
            } else {
                boolean safe = accept("?"); // Apex safe navigation: a?.b
                advance(); // '.'
                String member = advance().value();
                expr = at("(")
                    ? new MethodCall(expr, member, parseArgs(), safe)
                    : new Prop(expr, member, safe);
            }
        }
        // trailing post-increment/decrement as an expression: i++, list.get(0)--
        if (at("++") || at("--")) {
            expr = new Postfix(expr, advance().value());
        }
        return expr;
    }

    private Expr parsePrimary() {
        Token t = peek();
        if (t.kind().equals("DQUOTE")) {
            throw new RuntimeException(
                "Apex strings use single quotes ('), not double quotes (\") (" + lineOf(t) + ")");
        }
        if (t.kind().equals("NUMBER")) {
            advance();
            return t.value().contains(".") ? new DecimalLit(t.value()) : new Num(Integer.parseInt(t.value()));
        }
        if (t.kind().equals("STRING")) {
            advance();
            return new Str(t.value().substring(1, t.value().length() - 1));
        }
        if (at("[")) return parseSoql();
        if (at("(")) {
            // cast?  (Type) operand   — only when '(' Type ')' is followed by an operand
            int save = i;
            advance();
            String castType = consumeType();
            if (castType != null && at(")") && startsOperand(peek(1))) {
                advance(); // ')'
                return new Cast(castType, parseUnary());
            }
            i = save;
            advance();
            Expr e = parseExpr();
            expect(")");
            return e;
        }
        if (t.value().equalsIgnoreCase("new")) return parseNew();
        if (t.kind().equals("IDENT")) {
            // case-insensitive keyword literals (TRUE/False/NULL); the canonical Java
            // literal is emitted, the source token's case is irrelevant past this point
            if (t.value().equalsIgnoreCase("true") || t.value().equalsIgnoreCase("false")) {
                advance();
                return new Bool(t.value().equalsIgnoreCase("true"));
            }
            if (t.value().equalsIgnoreCase("null")) {
                advance();
                return new Null();
            }
            String id = advance().value();
            // type literal: Foo.class
            if (at(".") && peek(1).value().equalsIgnoreCase("class")) {
                advance();
                advance();
                return new ClassLit(id);
            }
            // type literal with generics: List<Account>.class (else it's a comparison)
            if (at("<")) {
                int g = i;
                String generic = consumeGeneric();
                if (at(".") && peek(1).value().equalsIgnoreCase("class")) {
                    advance();
                    advance();
                    return new ClassLit(id + generic);
                }
                i = g;
            }
            // a leading call stays a Call; '.' chains are handled by parsePostfix
            if (at("(")) return new Call(id, parseArgs());
            return new Name(id);
        }
        throw new RuntimeException("unexpected token '" + t.value() + "' (" + lineOf(t) + ")");
    }

    private Expr parseNew() {
        expect("new");
        String type = consumeType();
        if (at("[")) {
            // new T[n] -> a List<T> sized to n (Apex pre-fills with nulls)
            advance();
            Expr size = parseExpr();
            expect("]");
            return new ArrayNew(type, size);
        }
        if (at("{")) {
            return parseCollectionLiteral(type);
        }
        expect("(");
        // named args (Field = value) => sObject literal; positional => constructor
        if (isIdent() && peek(1).value().equals("=")) {
            List<FieldInit> fields = new ArrayList<>();
            while (!at(")")) {
                String fname = advance().value();
                expect("=");
                fields.add(new FieldInit(fname, parseExpr()));
                if (!accept(",")) break;
            }
            expect(")");
            return new SObjectLit(type, fields);
        }
        List<Expr> args = new ArrayList<>();
        if (!at(")")) {
            args.add(parseExpr());
            while (accept(",")) args.add(parseExpr());
        }
        expect(")");
        return new New(type, args);
    }

    // new List<X>{a,b} / new Set<X>{a,b} / new Map<K,V>{k => v}
    private Expr parseCollectionLiteral(String type) {
        expect("{");
        String base = type;
        int lt = base.indexOf('<');
        if (lt >= 0) base = base.substring(0, lt);
        boolean isMap = base.equalsIgnoreCase("Map"); // Apex: map<> == Map<>
        List<Expr> keys = new ArrayList<>();
        List<Expr> values = new ArrayList<>();
        while (!at("}")) {
            Expr first = parseExpr();
            if (isMap) {
                expect("=>");
                keys.add(first);
                values.add(parseExpr());
            } else {
                values.add(first);
            }
            if (!accept(",")) break;
        }
        expect("}");
        return isMap ? new MapLit(type, keys, values) : new ListLit(type, values);
    }

    private List<Expr> parseArgs() {
        expect("(");
        List<Expr> args = new ArrayList<>();
        if (!at(")")) {
            args.add(parseExpr());
            while (accept(",")) args.add(parseExpr());
        }
        expect(")");
        return args;
    }

    private Expr parseSoql() {
        expect("[");
        int start = peek().start();
        List<Bind> binds = new ArrayList<>();
        while (!at("]") && !peek().kind().equals("EOF")) {
            if (at(":")) {
                advance();
                StringBuilder name = new StringBuilder(advance().value());
                while (at(".")) {
                    advance();
                    name.append('.').append(advance().value());
                }
                binds.add(new Bind(name.toString().replace(".", "_"), new Name(name.toString())));
            } else {
                advance();
            }
        }
        int end = peek().start();
        expect("]");
        return new Soql(src.substring(start, end).strip(), binds);
    }
}
