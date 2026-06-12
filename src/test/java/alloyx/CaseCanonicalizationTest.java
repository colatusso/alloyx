// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
}
