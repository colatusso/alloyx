package alloyx;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles a set of .cls together (Apex has no imports — all classes share one
 * namespace), so a class can call another. Generated .java is written to
 * .apexcache/ (inspectable), compiled in one javac pass, and loaded from a
 * shared classloader. All classes thus run against the same configured org.
 */
final class Workspace {
    static final Path CACHE_DIR = Path.of(".apexcache");

    // Names that look like a type but never describe to an sObject, so we don't waste a
    // describe call on them. (User classes and *Exception are filtered separately.)
    private static final Set<String> NON_SOBJECT = Set.of(
        "List", "Set", "Map", "Integer", "Long", "Double", "Boolean", "String", "Object",
        "Decimal", "Date", "Datetime", "Time", "Id", "Blob", "void", "SObject", "System",
        "Math", "Database", "JSON", "UserInfo", "Strings", "Schema", "Test", "Trigger",
        "Exception", "Iterable", "Iterator");

    record Compiled(ClassLoader loader, List<ClassDecl> classes) {
        Class<?> load(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }
    }

    static Compiled compile(List<Path> clsFiles) throws Exception {
        List<ClassDecl> decls = new ArrayList<>();
        for (Path f : clsFiles) {
            try {
                decls.add(Parser.parse(Files.readString(f)));
            } catch (RuntimeException e) {
                throw new RuntimeException(f.getFileName() + ": " + e.getMessage(), e);
            }
        }
        Set<String> userClasses = new HashSet<>();
        for (ClassDecl d : decls) {
            userClasses.add(d.name());
        }

        Files.createDirectories(CACHE_DIR);
        // schema typing for sObject field access; no org -> describes return null (untyped)
        var schema = new alloyx.runtime.SchemaCache(alloyx.runtime.Database.gateway());

        // Which referenced sObjects can we type? Only those the schema can describe
        // (synced to cache, or an org is connected). Everything else stays the generic
        // SObject — so with no sync the generated Java is exactly what it was before.
        Set<String> typedSObjects = new LinkedHashSet<>();
        for (String name : SObjectScan.referenced(decls)) {
            if (!userClasses.contains(name) && !NON_SOBJECT.contains(name)
                    && !name.endsWith("Exception") && schema.isDescribed(name)) {
                typedSObjects.add(name);
            }
        }

        List<String> javaFiles = new ArrayList<>();
        // a generated typed class per described sObject (compiled alongside the user classes)
        for (String name : typedSObjects) {
            String src = SObjectClassGen.generate(name, schema.fields(name), typedSObjects);
            Path javaFile = CACHE_DIR.resolve(name + ".java");
            Files.writeString(javaFile, src);
            javaFiles.add(javaFile.toString());
        }
        for (ClassDecl d : decls) {
            Transpiler.Result r = Transpiler.transpile(d, userClasses, schema, typedSObjects);
            Path javaFile = CACHE_DIR.resolve(d.name() + ".java");
            Files.writeString(javaFile, r.source());
            javaFiles.add(javaFile.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("no system Java compiler available (run on a JDK, not a JRE)");
        }
        String classpath = java.lang.System.getProperty("java.class.path");
        List<String> args = new ArrayList<>(List.of("-cp", classpath, "-d", CACHE_DIR.toString()));
        args.addAll(javaFiles);
        int rc = compiler.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) {
            throw new RuntimeException("javac failed (see diagnostics above)");
        }

        URLClassLoader loader = new URLClassLoader(
            new URL[]{CACHE_DIR.toUri().toURL()}, Workspace.class.getClassLoader());
        return new Compiled(loader, decls);
    }

    /**
     * For `run`: the target file plus the sibling classes it transitively
     * references by name. Sibling classes are indexed by file name (the SF
     * convention: file name == class name), and references are found by a cheap
     * token scan, so we never parse — let alone drag in — unrelated .cls.
     */
    static List<Path> resolveDeps(Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Map<String, Path> index = new HashMap<>();
        if (dir != null) {
            for (Path p : clsAt(dir)) {
                index.put(classNameOf(p), p);
            }
        }
        String start = classNameOf(target);
        index.putIfAbsent(start, target.toAbsolutePath());

        LinkedHashMap<String, Path> closure = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            Path f = index.get(name);
            if (f == null || closure.containsKey(name)) {
                continue;
            }
            closure.put(name, f);
            for (Lexer.Token t : Lexer.tokenize(Files.readString(f))) {
                if (t.kind().equals("IDENT") && index.containsKey(t.value())) {
                    queue.add(t.value());
                }
            }
        }
        return new ArrayList<>(closure.values());
    }

    /**
     * The sObject-looking type names referenced by the given files — what
     * {@code allx schema sync} describes and caches. Tolerant: files that don't
     * parse are skipped (discovery shouldn't fail on one bad class).
     */
    static Set<String> candidateSObjects(List<Path> clsFiles) throws IOException {
        List<ClassDecl> decls = new ArrayList<>();
        Set<String> userClasses = new HashSet<>();
        for (Path f : clsFiles) {
            try {
                ClassDecl d = Parser.parse(Files.readString(f));
                decls.add(d);
                userClasses.add(d.name());
            } catch (RuntimeException | StackOverflowError skip) {
                // unparseable/pathological file: ignore for discovery, never abort the scan
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (String name : SObjectScan.referenced(decls)) {
            if (!userClasses.contains(name) && !NON_SOBJECT.contains(name)
                    && !name.endsWith("Exception")) {
                out.add(name);
            }
        }
        return out;
    }

    /** One editor diagnostic, in coordinates of the original .cls. */
    record Diag(String severity, int line, int column, String message) {}

    /**
     * Type-check a single .cls for editor feedback (the `allx check` MVP): compile ONLY
     * the target (plus any typed sObject classes the schema can describe), collect javac
     * diagnostics, keep only the target's, and translate their lines back to the .cls.
     *
     * Deps aren't compiled here, so references to other org classes surface as "cannot
     * find symbol". Those whose name is a known workspace class are dropped (just not
     * loaded); an unknown name is kept — it's a real first-level typo. Self-contained
     * checks (primitives, own members, and sObject fields once a schema is synced) are
     * validated for real.
     */
    static List<Diag> check(Path target) throws Exception {
        String src = Files.readString(target);
        Parser.Parsed parsed;
        try {
            parsed = Parser.parseWithLines(src);
        } catch (RuntimeException syntaxError) {
            return List.of(syntaxDiag(syntaxError.getMessage()));
        }
        ClassDecl cls = parsed.cls();

        Files.createDirectories(CACHE_DIR);
        var schema = new alloyx.runtime.SchemaCache(alloyx.runtime.Database.gateway());
        List<ClassDecl> decls = List.of(cls);
        Set<String> userClasses = new HashSet<>(Set.of(cls.name()));
        Set<String> typedSObjects = new LinkedHashSet<>();
        for (String name : SObjectScan.referenced(decls)) {
            if (!userClasses.contains(name) && !NON_SOBJECT.contains(name)
                    && !name.endsWith("Exception") && schema.isDescribed(name)) {
                typedSObjects.add(name);
            }
        }

        List<String> javaFiles = new ArrayList<>();
        for (String name : typedSObjects) {
            Path jf = CACHE_DIR.resolve(name + ".java");
            Files.writeString(jf, SObjectClassGen.generate(name, schema.fields(name), typedSObjects));
            javaFiles.add(jf.toString());
        }
        Transpiler.Result r = Transpiler.transpileWithLines(
            cls, userClasses, schema, typedSObjects, parsed.stmtLines());
        Path targetJava = CACHE_DIR.resolve(cls.name() + ".java");
        Files.writeString(targetJava, r.source());
        javaFiles.add(targetJava.toString());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("no system Java compiler available (run on a JDK, not a JRE)");
        }
        var collected = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var fm = compiler.getStandardFileManager(collected, null, java.nio.charset.StandardCharsets.UTF_8);
        String classpath = java.lang.System.getProperty("java.class.path");
        List<String> options = List.of("-cp", classpath, "-d", CACHE_DIR.toString());
        compiler.getTask(null, fm, collected, options, null,
            fm.getJavaFileObjectsFromStrings(javaFiles)).call();
        fm.close();

        Set<String> known = workspaceClassNames(target);
        java.util.NavigableMap<Integer, Integer> map = new java.util.TreeMap<>(r.lineMap());
        String targetJavaName = cls.name() + ".java";
        List<Diag> out = new ArrayList<>();
        for (var d : collected.getDiagnostics()) {
            if (d.getSource() == null || !d.getSource().getName().endsWith(targetJavaName)) {
                continue; // only the file the dev is editing
            }
            String msg = d.getMessage(null);
            if (isMissingKnownSymbol(msg, known)) {
                continue; // a dep not loaded here, not the dev's mistake
            }
            int apexLine = mapLine(map, (int) d.getLineNumber());
            out.add(new Diag(d.getKind().toString(), apexLine, (int) d.getColumnNumber(),
                apexify(msg)));
        }
        return out;
    }

    // Rewrite a javac message in Apex terms: the runtime/Java type names the dev never
    // wrote (java.lang.String -> String, int -> Integer, BigDecimal -> Decimal, the
    // alloyx.runtime.* wrappers -> their bare names). Pure string cleanup, no semantics.
    private static String apexify(String msg) {
        return msg.replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .replace("java.math.BigDecimal", "Decimal")
            .replaceAll("\\bjava\\.lang\\.", "")
            .replaceAll("\\balloyx\\.runtime\\.", "")
            .replaceAll("\\bint\\b", "Integer")
            .replaceAll("\\bboolean\\b", "Boolean")
            .replaceAll("\\bdouble\\b", "Double")
            .replaceAll("\\blong\\b", "Long")
            .trim();
    }

    // a parser error reads "... (line N)"; surface it as a syntax diagnostic on that line
    private static Diag syntaxDiag(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+)").matcher(message);
        int line = m.find() ? Integer.parseInt(m.group(1)) : 1;
        return new Diag("ERROR", line, 1, message);
    }

    // "cannot find symbol" whose missing name is a known org class = just a dep we didn't
    // load here (not the dev's mistake). Read the name after "symbol:" — NOT the "location:"
    // line, which carries the enclosing class and would wrongly match.
    private static final java.util.regex.Pattern MISSING_SYMBOL =
        java.util.regex.Pattern.compile("symbol:\\s+(?:variable|class|method|type)\\s+(\\w+)");

    private static boolean isMissingKnownSymbol(String msg, Set<String> known) {
        if (!msg.contains("cannot find symbol")) {
            return false;
        }
        java.util.regex.Matcher m = MISSING_SYMBOL.matcher(msg);
        return m.find() && known.contains(m.group(1));
    }

    // generated-Java error line -> the .cls line of the statement that contains it
    private static int mapLine(java.util.NavigableMap<Integer, Integer> map, int javaLine) {
        java.util.Map.Entry<Integer, Integer> e = map.floorEntry(javaLine);
        return e != null ? e.getValue() : (map.isEmpty() ? javaLine : map.firstEntry().getValue());
    }

    // names of every .cls in the target's workspace (the org classes; deps not compiled here)
    private static Set<String> workspaceClassNames(Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Set<String> names = new HashSet<>();
        if (dir != null) {
            for (Path p : clsAt(dir)) {
                names.add(classNameOf(p));
            }
        }
        return names;
    }

    private static String classNameOf(Path clsFile) {
        String n = clsFile.getFileName().toString();
        return n.endsWith(".cls") ? n.substring(0, n.length() - ".cls".length()) : n;
    }

    /** A single file compiles alone; a directory compiles all its .cls together. */
    static List<Path> clsAt(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        try (Stream<Path> s = Files.walk(path)) {
            return s.filter(p -> p.toString().endsWith(".cls"))
                .filter(p -> !underHiddenDir(path, p))
                .sorted().toList();
        }
    }

    /**
     * True if {@code file} lives under a hidden (dot-prefixed) directory below the
     * scan root. Skips tooling/VCS trees — {@code .git}, {@code .apexcache}, and
     * crucially {@code .sfdx/.../StandardApexLibrary}, whose stubs (e.g. an
     * {@code enum LoggingLevel}) are not user Apex and collide with the runtime.
     * A root that is itself hidden is honoured; only segments below it are pruned.
     */
    private static boolean underHiddenDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            if (rel.getName(i).toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }
}
