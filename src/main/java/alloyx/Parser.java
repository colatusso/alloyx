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

    private Parser(List<Token> toks, String src) {
        this.toks = toks;
        this.src = src;
    }

    static ClassDecl parse(String src) {
        return new Parser(Lexer.tokenize(src), src).parseClass();
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

    private boolean at(String value) {
        return peek().value().equals(value);
    }

    private boolean isIdent() {
        return peek().kind().equals("IDENT");
    }

    // could this token begin a unary operand right after a cast's closing ')'
    private boolean startsOperand(Token t) {
        if (t.kind().equals("NUMBER") || t.kind().equals("STRING")) return true;
        if (t.value().equals("(") || t.value().equals("new")) return true;
        return t.kind().equals("IDENT") && !t.value().equals("instanceof");
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
        if (!t.value().equals(value)) {
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

    // --- class
    private ClassDecl parseClass() {
        parseAnnotations();
        while (isIdent() && !at("class")) {
            advance(); // skip modifiers
        }
        expect("class");
        String name = advance().value();
        String superclass = null;
        if (accept("extends")) {
            superclass = consumeType();
        }
        if (accept("implements")) {
            consumeType();
            while (accept(",")) consumeType();
        }
        expect("{");
        List<MethodDecl> methods = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        while (!at("}")) {
            Object member = parseMember(name);
            if (member == null) continue; // tolerated inner type / static{} block
            if (member instanceof Field f) fields.add(f);
            else if (member instanceof List<?> group) {
                for (Object g : group) fields.add((Field) g); // several fields on one line
            } else methods.add((MethodDecl) member);
        }
        expect("}");
        return new ClassDecl(name, methods, fields, superclass);
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
        boolean isStatic = parts.subList(0, parts.size() - 1).contains("static");
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
            return new MethodDecl(name, isStatic, returnType, params, body, anns, lineNum(memberStart));
        }
        if (at("{")) {
            // property (Type name { get; set; }) or static{}/inner-type block
            boolean isTypeMember = parts.contains("class") || parts.contains("interface")
                || parts.contains("enum");
            skipBalancedBraces();
            accept(";"); // tolerate a trailing ';'
            if (isTypeMember || parts.size() < 2) {
                return null; // inner type or static initializer — tolerated, not emitted
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
        String kw = peek().value();
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
        accept("final"); // optional local modifier, no Java equivalent needed
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
        accept("final");
        String type = consumeType();
        if (type != null && isIdent()) {
            String name = advance().value();
            Expr init = accept("=") ? parseExpr() : null;
            return new VarDecl(type, name, init);
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

    // --- expressions (precedence low -> high)
    private Expr parseExpr() {
        return parseTernary();
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
        while (at("==") || at("!=")) {
            String op = advance().value();
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
        while (at("[") || at(".")) {
            if (accept("[")) {
                Expr index = parseExpr();
                expect("]");
                expr = new Index(expr, index);
            } else {
                advance(); // '.'
                String member = advance().value();
                expr = at("(") ? new MethodCall(expr, member, parseArgs()) : new Prop(expr, member);
            }
        }
        return expr;
    }

    private Expr parsePrimary() {
        Token t = peek();
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
        if (t.value().equals("new")) return parseNew();
        if (t.kind().equals("IDENT")) {
            if (t.value().equals("true") || t.value().equals("false")) {
                advance();
                return new Bool(t.value().equals("true"));
            }
            if (t.value().equals("null")) {
                advance();
                return new Null();
            }
            String id = advance().value();
            // type literal: Foo.class
            if (at(".") && peek(1).value().equals("class")) {
                advance();
                advance();
                return new ClassLit(id);
            }
            // type literal with generics: List<Account>.class (else it's a comparison)
            if (at("<")) {
                int g = i;
                String generic = consumeGeneric();
                if (at(".") && peek(1).value().equals("class")) {
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
        boolean isMap = base.equals("Map");
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
