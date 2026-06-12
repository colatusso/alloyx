// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sObject scan reports identifiers AS WRITTEN — including a plain variable named
 * {@code account} whose member access (account.Id) makes it a typed-sObject candidate. The
 * describe filter is case-insensitive, so both {@code account} and {@code Account} pass; on a
 * case-insensitive filesystem (macOS APFS) the two generated .java clobber each other and the
 * class named by the declared type vanishes ("cannot find symbol: class Account"). The filter
 * must canonicalize every candidate to the org's API casing, deduping the set.
 */
class CaseCanonicalizationTest {

    @TempDir
    Path dir;

    /** Describes Account (any casing) and publishes a global list with the canonical name. */
    static final class AccountGateway implements OrgGateway {
        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            return new alloyx.runtime.List<>();
        }

        @Override
        public void insert(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void update(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void delete(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public Map<String, String> describe(String sobjectType) {
            if (sobjectType.equalsIgnoreCase("Account")) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("Id", "Id");
                f.put("Name", "String");
                f.put("Status__c", "String");
                return f;
            }
            return null;
        }

        @Override
        public java.util.Set<String> globalSObjects() {
            return java.util.Set.of("Account");
        }
    }

    @BeforeEach
    void connect() throws Exception {
        cleanSchemaCache();
        Database.setGateway(new AccountGateway());
    }

    @AfterEach
    void disconnect() throws Exception {
        Database.setGateway(new UnconnectedGateway());
        cleanSchemaCache();
    }

    private void cleanSchemaCache() throws Exception {
        Path schema = Workspace.CACHE_DIR.resolve("schema");
        if (Files.isDirectory(schema)) {
            try (var w = Files.walk(schema)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void variableNamedLikeSObject_doesNotClobberGeneratedClass() throws Exception {
        // for (Account account : ...) { account.Id ... } — the loop VAR name `account` is scanned
        // as a candidate alongside the TYPE `Account`; without canonicalization the two generated
        // files collide on APFS and the check reports "cannot find symbol: class Account".
        Path f = dir.resolve("AccountFilter.cls");
        Files.writeString(f, """
            public class AccountFilter {
                public static List<Account> blocked(List<Account> newAccounts, Map<Id, Account> old) {
                    List<Account> out = new List<Account>();
                    for (Account account : newAccounts) {
                        if (old.get(account.Id).Status__c == 'Blocked'
                                && account.Status__c != 'Blocked') {
                            out.add(account);
                        }
                    }
                    return out;
                }
            }
            """);
        var diags = Workspace.check(f, null, dir.resolve(".apexcache"));
        assertTrue(diags.isEmpty(), "expected a clean check, got: " + diags);
    }

    // ---- case-insensitive typed-sObject resolution (Apex isn't case-sensitive) ----
    // After canonicalization the set holds ONLY the org casing (Account). A declaration written in
    // another case — `account acc;`, `new account()`, both legal Apex — must still resolve to that
    // typed class, and the emitted Java must use the canonical `Account` so it links against the
    // generated `class Account`. Otherwise the type degrades to the dynamic SObject and cascades
    // ("Object cannot be converted to ...", "cannot find symbol: method getX()").

    /** A lowercase type declaration + lowercase ctor compiles, runs, and emits canonical `Account`. */
    @Test
    void lowercaseTypeDeclAndCtor_compilesRunsAndEmitsCanonical() throws Exception {
        Path p = dir.resolve("Lower.cls");
        Files.writeString(p, """
            public class Lower {
                public static String build() {
                    account acc = new account(Name = 'x');
                    return acc.Name;
                }
            }
            """);
        Path cache = dir.resolve(".apexcache");
        Workspace.Compiled c = Workspace.compile(List.of(p), List.of(), cache);
        Class<?> k = c.load("Lower");
        assertEquals("x", k.getMethod("build").invoke(null));

        // source-shape: the emitted Java uses the canonical `Account` class (the generated class the
        // ctor links against), and the lowercase `account` type never leaks through.
        String emitted = Files.readString(cache.resolve("Lower.java"));
        assertTrue(emitted.contains("new Account()"), "expected canonical ctor, got:\n" + emitted);
        assertTrue(!emitted.contains("new account(") && !emitted.contains("account acc"),
            "lowercase type leaked into the emitted Java:\n" + emitted);
    }

    /** A lowercase type in a method SIGNATURE (param + return) across classes compiles and links. */
    @Test
    void lowercaseTypeInSignature_crossClass() throws Exception {
        Path gw = dir.resolve("Gateway.cls");
        Files.writeString(gw, """
            public class Gateway {
                public static account tag(account a) {
                    a.Status__c = 'ok';
                    return a;
                }
            }
            """);
        Path caller = dir.resolve("Caller.cls");
        Files.writeString(caller, """
            public class Caller {
                public static String run() {
                    Account a = new Account(Name = 'y');
                    Account tagged = Gateway.tag(a);
                    return tagged.Status__c;
                }
            }
            """);
        Path cache = dir.resolve(".apexcache");
        Workspace.Compiled c = Workspace.compile(List.of(gw, caller), List.of(), cache);
        Class<?> k = c.load("Caller");
        assertEquals("ok", k.getMethod("run").invoke(null));

        // the signature's lowercase `account` must emit as the canonical `Account` param/return type
        String emitted = Files.readString(cache.resolve("Gateway.java"));
        assertTrue(emitted.contains("Account tag(Account a)"),
            "expected canonical signature, got:\n" + emitted);
    }

    /** Mixed casings of one object in one file generate ONE class and compile. */
    @Test
    void mixedCasingInOneFile_generatesOneClassAndCompiles() throws Exception {
        Path p = dir.resolve("Mixed.cls");
        Files.writeString(p, """
            public class Mixed {
                public static String build() {
                    Account upper = new Account(Name = 'U');
                    account lower = new account(Name = 'L');
                    return upper.Name + lower.Name;
                }
            }
            """);
        Path cache = dir.resolve(".apexcache");
        Workspace.Compiled c = Workspace.compile(List.of(p), List.of(), cache);
        Class<?> k = c.load("Mixed");
        assertEquals("UL", k.getMethod("build").invoke(null));

        // exactly one generated sObject class file (canonical Account), not a colliding pair
        try (var s = Files.list(cache)) {
            long accountClasses = s.filter(f -> {
                String n = f.getFileName().toString();
                return n.equalsIgnoreCase("Account.java");
            }).count();
            assertEquals(1, accountClasses, "expected exactly one generated Account.java");
        }
    }
}
