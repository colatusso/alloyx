// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import alloyx.runtime.Database;
import alloyx.runtime.SalesforceGateway;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI: run / test / transpile. Org-bound calls route to the org set by --org or
 * by alloyx.json (config), so SOQL/DML/sObject hit the real org.
 */
public final class Cli {
    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        // --version first: print only the version and exit, so a caller (e.g. the IDE
        // extension) can probe whether the installed CLI is new enough.
        if (cmd.equals("--version") || cmd.equals("version")) {
            String v = Cli.class.getPackage().getImplementationVersion();
            java.lang.System.out.println(v != null ? v : "dev");
            return;
        }
        switch (cmd) {
            case "run" -> run(args);
            case "eval" -> eval(args);
            case "check" -> check(args);
            case "test" -> test(args);
            case "transpile" -> {
                String source = Files.readString(Path.of(args[1]));
                java.lang.System.out.println(Transpiler.transpile(Parser.parse(source)).source());
            }
            case "outline" -> outline(args);
            case "schema" -> schema(args);
            default -> {
                java.lang.System.err.println(
                    "usage: allx (run <File.cls> --method Class.method [--args v1 v2 ...] "
                        + "| eval (<File>|--stdin) [--dir <classesDir>] "
                        + "| check <File.cls> [--stdin] "
                        + "| test <path> | transpile <File.cls> | outline <File.cls> "
                        + "| schema (sync (<path>|--obj A,B) | refresh)) [--org alias] "
                        + "| --version");
                java.lang.System.exit(2);
            }
        }
    }

    /**
     * `allx schema sync <path> [--org alias]` describes every sObject referenced by
     * the code and caches it, so typed runs work offline afterwards.
     * `allx schema refresh` drops the cache so the next sync re-fetches.
     */
    private static void schema(String[] args) throws Exception {
        String sub = args.length > 1 ? args[1] : "";
        switch (sub) {
            case "sync" -> syncSchema(args);
            case "refresh" -> {
                String pathArg = args.length > 2 && !args[2].startsWith("--") ? args[2] : ".";
                Path d = Config.cacheDir(Path.of(pathArg)).resolve("schema");
                if (Files.exists(d)) {
                    try (var w = Files.walk(d)) {
                        w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                }
                java.lang.System.out.println("schema cache cleared");
            }
            default -> java.lang.System.err.println(
                "usage: allx schema (sync (<path> | --obj A,B) [--org alias] | refresh)");
        }
    }

    /** Describe + cache every referenced sObject so later runs type them offline. */
    private static void syncSchema(String[] args) throws Exception {
        String pathArg = args.length > 2 && !args[2].startsWith("--") ? args[2] : ".";
        String org = null;
        // --obj Account,Contact: describe these exact sObjects instead of scanning code
        java.util.LinkedHashSet<String> explicit = new java.util.LinkedHashSet<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--org")) {
                org = args[++i];
            } else if (args[i].equals("--obj")) {
                for (String n : args[++i].split(",")) {
                    if (!n.trim().isEmpty()) {
                        explicit.add(n.trim());
                    }
                }
            }
        }
        Path path = Path.of(pathArg);
        connectOrg(org, path);
        // anchor to the project root so a later `allx check` (run from the editor's CWD)
        // finds this synced schema; otherwise sync and check could write/read different dirs.
        Path schemaDir = Config.cacheDir(path).resolve("schema");
        var cache = new alloyx.runtime.SchemaCache(Database.gateway(), schemaDir, Long.MAX_VALUE);
        boolean discovered = explicit.isEmpty();
        java.util.Set<String> raw = discovered
            ? Workspace.candidateSObjects(Workspace.clsAt(path))
            : explicit;

        // keep only real sObjects: drop relationships (__r), non-type tokens (lowercase
        // keywords/vars), and — once the org's global list is loaded — anything that isn't
        // actually an object (Apex classes, mocks, wrappers a code scan inevitably picks up)
        java.util.Set<String> known = cache.knownSObjects();
        java.util.LinkedHashSet<String> objs = new java.util.LinkedHashSet<>();
        int dropped = 0;
        for (String o : raw) {
            boolean keep = !o.endsWith("__r")
                && (!discovered || Character.isUpperCase(o.charAt(0)))
                && cache.isKnownSObject(o);
            if (keep) {
                objs.add(o);
            } else {
                dropped++;
            }
        }

        int total = objs.size();
        java.lang.System.out.println("describing " + total + " sObject(s)"
            + (dropped > 0 ? " (" + dropped + " non-object refs skipped)" : "")
            + (known == null ? " — no org global list, best-effort" : "") + "...");
        int synced = 0;
        int i = 0;
        for (String o : objs) {
            // streamed progress: each line prints as its describe completes, with an i/N
            // counter so a long sync (many objects) shows how much is left
            String at = "[" + (++i) + "/" + total + "] ";
            cache.refresh(o); // force a fresh describe
            java.util.Map<String, String> fields = cache.fields(o);
            if (fields == null || fields.isEmpty()) {
                java.lang.System.out.println(at + "skip   " + o + " (not describable)");
                continue;
            }
            java.lang.System.out.println(at + "synced " + o + " (" + fields.size() + " fields)");
            synced++;
        }
        java.lang.System.out.println(
            "\n" + synced + "/" + total + " sObject(s) cached in " + schemaDir
                + " — runs are now typed offline");
    }

    /** Emits one line of JSON describing the class's methods (line, flags, params) for the IDE. */
    private static void outline(String[] args) throws Exception {
        Path file = Path.of(args[1]);
        ClassDecl cls = Parser.parse(Files.readString(file));
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("file", file.getFileName().toString());
        root.put("class", cls.name());
        java.util.List<Object> methods = new java.util.ArrayList<>();
        for (MethodDecl m : cls.methods()) {
            java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
            mm.put("name", m.name());
            mm.put("line", m.line());
            mm.put("static", m.isStatic());
            mm.put("isTest", m.isTest());
            mm.put("returnType", m.returnType());
            java.util.List<Object> ps = new java.util.ArrayList<>();
            for (Param p : m.params()) {
                java.util.Map<String, Object> pp = new java.util.LinkedHashMap<>();
                pp.put("type", p.type());
                pp.put("name", p.name());
                ps.add(pp);
            }
            mm.put("params", ps);
            methods.add(mm);
        }
        root.put("methods", methods);
        java.lang.System.out.println(new com.google.gson.Gson().toJson(root));
    }

    /**
     * `allx check <File.cls> [--org alias]`: type-check a single class for editor
     * feedback and print the diagnostics as JSON (one array of {severity,line,column,
     * message}, in .cls coordinates). The VS Code extension consumes this.
     */
    private static void check(String[] args) throws Exception {
        Path target = Path.of(args[1]);
        String org = null;
        boolean stdin = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--org")) {
                org = args[++i];
            } else if (args[i].equals("--stdin")) {
                stdin = true; // read the (unsaved) source from stdin, for live editor checks
            }
        }
        connectOrg(org, target); // schema (typed sObjects) via org or synced cache; offline otherwise
        String source = stdin
            ? new String(java.lang.System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            : null;
        // anchor the cache to the project root (walk up for alloyx.json/.apexcache), not the
        // raw CWD — the editor invokes us with cwd=dirname(file), which otherwise misses a
        // synced schema in a nested layout and degrades every sObject to untyped.
        java.util.List<Workspace.Diag> diags = Workspace.check(target, source, Config.cacheDir(target));
        // disableHtmlEscaping so generics read as <String>, not unicode escapes
        java.lang.System.out.println(
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(diags));
    }

    /** Connect to the resolved org (--org, else alloyx.json); returns the alias, or null if none. */
    private static String connectOrg(String cliOrg, Path start) {
        String org = cliOrg != null ? cliOrg : Config.findOrg(start).orElse(null);
        if (org != null) {
            Database.setGateway(new SalesforceGateway(org));
        }
        return org;
    }

    private static void run(String[] args) throws Exception {
        Path file = Path.of(args[1]);
        String method = null;
        String org = null;
        List<String> callArgs = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--method")) {
                method = args[++i];
            } else if (args[i].equals("--org")) {
                org = args[++i];
            } else if (args[i].equals("--args")) {
                // everything up to the next --flag is a positional argument
                while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    callArgs.add(args[++i]);
                }
            }
        }
        connectOrg(org, file);

        // compile the target plus only the sibling classes it references — never the
        // whole folder (a real project's src/classes has hundreds of unrelated .cls)
        Workspace.Compiled compiled =
            Workspace.compile(Workspace.resolveDeps(file), List.of(), Config.cacheDir(file));

        String className = method.contains(".") ? method.substring(0, method.indexOf('.')) : method;
        String methodName = method.contains(".") ? method.substring(method.indexOf('.') + 1) : method;

        Class<?> clazz = compiled.load(className);
        java.lang.reflect.Method m = findMethod(clazz, methodName, callArgs.size());
        // static -> invoke on null; instance method -> new up the class (no-arg ctor)
        Object receiver = java.lang.reflect.Modifier.isStatic(m.getModifiers())
            ? null
            : clazz.getDeclaredConstructor().newInstance();
        Object result = m.invoke(receiver, coerceArgs(m.getParameterTypes(), callArgs));
        if (m.getReturnType() != void.class) {
            java.lang.System.out.println("=> " + result);
        }
    }

    /**
     * `allx eval (<File>|--stdin) [--dir <classesDir>] [--org alias]`: run an
     * anonymous Apex block locally. The snippet is wrapped in a throwaway class,
     * compiled together with the workspace classes it references (resolved from
     * --dir), and executed on the JVM — System.debug prints to stdout. This is a
     * local Execute Anonymous: the editor uses it to invoke a method with the
     * arguments the developer fills in, but it runs any Apex statements.
     */
    private static void eval(String[] args) throws Exception {
        String org = null;
        Path dir = Path.of(".");
        boolean stdin = false;
        String file = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--org" -> org = args[++i];
                case "--dir" -> dir = Path.of(args[++i]);
                case "--stdin" -> stdin = true;
                default -> {
                    if (!args[i].startsWith("--") && file == null) {
                        file = args[i];
                    }
                }
            }
        }
        String snippet = stdin
            ? new String(java.lang.System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            : (file != null ? Files.readString(Path.of(file)) : "");
        if (snippet.isBlank()) {
            java.lang.System.err.println("eval: nothing to run (pass a file or --stdin)");
            java.lang.System.exit(2);
            return;
        }
        String targetOrg = connectOrg(org, dir);
        // surface the org so a SOQL/DML run makes clear which org it hits (or that it's local)
        java.lang.System.out.println(targetOrg != null
            ? "org: " + targetOrg
            : "org: (none — local only; SOQL/DML will fail)");

        // Wrap the block in a static method of a throwaway class, then compile and
        // invoke it like any other workspace class. The name is unlikely to collide;
        // if a real class shared it, both would compile and javac would flag it.
        String scratchClass = "AlloyxScratch";
        String wrapped = "public class " + scratchClass + " {\n"
            + "  public static void run() {\n"
            + snippet + "\n"
            + "  }\n}\n";
        ClassDecl scratch;
        try {
            scratch = Parser.parse(wrapped);
        } catch (RuntimeException syntaxError) {
            java.lang.System.err.println("eval: " + syntaxError.getMessage());
            java.lang.System.exit(1);
            return;
        }

        List<Path> deps = Workspace.resolveDepsForSource(snippet, dir.toAbsolutePath());
        Workspace.Compiled compiled = Workspace.compile(deps, List.of(scratch), Config.cacheDir(dir));
        try {
            compiled.load(scratchClass).getMethod("run").invoke(null);
        } catch (InvocationTargetException ite) {
            // surface the Apex-level exception, not the reflection wrapper
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            java.lang.System.out.println(cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : ""));
            java.lang.System.exit(1);
        }
    }

    /** Pick the public method matching name + argument count (no overload by type). */
    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, int argc) {
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        throw new IllegalArgumentException("no method " + name + " taking " + argc + " argument(s)");
    }

    /** Parse the raw CLI strings into the method's parameter types. */
    private static Object[] coerceArgs(Class<?>[] types, List<String> raw) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            String v = raw.get(i);
            if (t == Integer.class || t == int.class) {
                out[i] = Integer.valueOf(v);
            } else if (t == Long.class || t == long.class) {
                out[i] = Long.valueOf(v);
            } else if (t == Double.class || t == double.class) {
                out[i] = Double.valueOf(v);
            } else if (t == Boolean.class || t == boolean.class) {
                out[i] = Boolean.valueOf(v);
            } else {
                out[i] = v;
            }
        }
        return out;
    }

    private static void test(String[] args) throws Exception {
        Path path = Path.of(args.length > 1 && !args[1].startsWith("--") ? args[1] : ".");
        String org = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--org")) {
                org = args[++i];
            }
        }
        connectOrg(org, path);

        Workspace.Compiled compiled =
            Workspace.compile(Workspace.clsAt(path), List.of(), Config.cacheDir(path));
        int passed = 0;
        int failed = 0;
        for (ClassDecl decl : compiled.classes()) {
            for (MethodDecl m : decl.methods()) {
                if (!m.isTest()) {
                    continue;
                }
                try {
                    compiled.load(decl.name()).getMethod(m.name()).invoke(null);
                    passed++;
                    java.lang.System.out.println("PASS  " + decl.name() + "." + m.name());
                } catch (Throwable t) {
                    failed++;
                    Throwable cause = t instanceof InvocationTargetException ite ? ite.getCause() : t;
                    java.lang.System.out.println("FAIL  " + decl.name() + "." + m.name());
                    java.lang.System.out.println("      " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
        }
        java.lang.System.out.println("\n" + passed + " passed, " + failed + " failed");
        java.lang.System.exit(failed > 0 ? 1 : 0);
    }
}
