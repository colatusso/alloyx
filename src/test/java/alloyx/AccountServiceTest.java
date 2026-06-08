package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.Database;
import alloyx.runtime.OrgConnectionException;
import alloyx.runtime.OrgGateway;
import alloyx.runtime.SObject;
import alloyx.runtime.UnconnectedGateway;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The headline: AccountService routes SOQL/DML through the org gateway. Only the
 * gateway (external boundary) is faked, so the wiring is verified without a live
 * org. Pointing it at a real org is just `set the alias` (config / --org).
 */
class AccountServiceTest {
    private static final Path SVC = Path.of("examples/AccountService.cls");

    /** Stands in for the real Salesforce middleware (only the boundary is faked). */
    static final class FakeGateway implements OrgGateway {
        final List<Map<String, Object>> rows;
        final List<SObject> inserted = new ArrayList<>();
        String lastSoql;
        Map<String, Object> lastBinds;

        FakeGateway(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public alloyx.runtime.List<SObject> query(String soql, Map<String, Object> binds) {
            lastSoql = soql;
            lastBinds = binds;
            alloyx.runtime.List<SObject> out = new alloyx.runtime.List<>();
            for (Map<String, Object> r : rows) {
                List<Object> kv = new ArrayList<>();
                r.forEach((k, v) -> {
                    kv.add(k);
                    kv.add(v);
                });
                out.add(new SObject("Account", kv.toArray()));
            }
            return out;
        }

        @Override
        public void insert(alloyx.runtime.List<SObject> records) {
            inserted.addAll(records);
        }

        @Override
        public void update(alloyx.runtime.List<SObject> records) {
        }

        @Override
        public void delete(alloyx.runtime.List<SObject> records) {
        }
    }

    @AfterEach
    void reset() {
        Database.setGateway(new UnconnectedGateway());
    }

    @Test
    void soqlAndDmlRouteThroughGateway() throws Exception {
        FakeGateway fake = new FakeGateway(List.of(
            Map.of("Id", "001", "Name", "Acme"),
            Map.of("Id", "002", "Name", "Acme")));
        Database.setGateway(fake);

        Class<?> svc = Workspace.compile(List.of(SVC)).load("AccountService");

        // SOQL -> gateway.query, with the exact query text and bound vars
        Object count = svc.getMethod("countByName", String.class).invoke(null, "Acme");
        assertEquals(Integer.valueOf(2), count);
        assertTrue(fake.lastSoql.contains("FROM Account WHERE Name = :name"), fake.lastSoql);
        assertEquals("Acme", fake.lastBinds.get("name"));

        // sObject literal: new Account(Name=..., Industry='Tech')
        SObject acc = (SObject) svc.getMethod("build", String.class).invoke(null, "New Co");
        assertEquals("Account", acc.getSObjectType().getName());
        assertEquals("New Co", acc.get("Name"));
        assertEquals("Tech", acc.get("Industry"));

        // DML statement -> gateway.insert
        svc.getMethod("touch", SObject.class).invoke(null, acc);
        assertEquals(1, fake.inserted.size());
        assertSame(acc, fake.inserted.get(0));
    }

    @Test
    void unconnectedGatewayRaisesClearError() throws Exception {
        Database.setGateway(new UnconnectedGateway());
        Class<?> svc = Workspace.compile(List.of(SVC)).load("AccountService");

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
            () -> svc.getMethod("countByName", String.class).invoke(null, "x"));
        assertTrue(ex.getCause() instanceof OrgConnectionException, String.valueOf(ex.getCause()));
    }
}
