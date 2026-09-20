// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatabaseTest {
    private final RecordingGateway gateway = new RecordingGateway();

    @AfterEach
    void resetGateway() {
        Database.setGateway(new UnconnectedGateway());
    }

    @Test
    void rejectsMultiRecordDmlBeforeCallingGateway() {
        Database.setGateway(gateway);
        List<SObject> records = new List<>();
        records.add(new SObject("Account", "Name", "A"));
        records.add(new SObject("Account", "Name", "B"));

        UnsupportedOperationException insert = assertThrows(
            UnsupportedOperationException.class, () -> Database.insert(records));
        UnsupportedOperationException update = assertThrows(
            UnsupportedOperationException.class, () -> Database.update(records));
        UnsupportedOperationException delete = assertThrows(
            UnsupportedOperationException.class, () -> Database.delete(records));
        UnsupportedOperationException upsert = assertThrows(
            UnsupportedOperationException.class, () -> Database.upsert(records));

        assertTrue(insert.getMessage().contains("allOrNone"), insert.getMessage());
        assertTrue(update.getMessage().contains("single-record"), update.getMessage());
        assertTrue(delete.getMessage().contains("single-record"), delete.getMessage());
        assertTrue(upsert.getMessage().contains("single-record"), upsert.getMessage());
        assertEquals(Set.of(), gateway.calls);
    }

    @Test
    void rejectsOptionAndExternalIdOverloadsBeforeCallingGateway() {
        Database.setGateway(gateway);
        SObject record = new SObject("Account", "Name", "A");

        assertThrows(UnsupportedOperationException.class, () -> Database.insert(record, true));
        assertThrows(UnsupportedOperationException.class, () -> Database.update(record, false));
        assertThrows(UnsupportedOperationException.class, () -> Database.delete(record, true));
        assertThrows(UnsupportedOperationException.class, () -> Database.upsert(record, "External_Id__c"));
        assertThrows(UnsupportedOperationException.class,
            () -> Database.upsert(record, "External_Id__c", false));

        assertEquals(Set.of(), gateway.calls);
    }

    @Test
    void keepsSingleRecordDmlSupported() {
        Database.setGateway(gateway);
        SObject record = new SObject("Account", "Name", "A");

        Database.insert(record);
        Database.update(record);
        Database.delete(record);
        Database.upsert(record);

        assertEquals(Set.of("insert", "update", "delete", "upsert"), gateway.calls);
    }

    @Test
    void blocksEvenSingleRecordDmlDuringLocalTestContext() throws Exception {
        Database.setGateway(gateway);
        SObject record = new SObject("Account", "Name", "A");
        alloyx.runtime.Test.runLocalTest(() -> {
            UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class, () -> Database.insert(record));
            assertTrue(error.getMessage().contains("transaction isolation"), error.getMessage());
            assertEquals(Set.of(), gateway.calls);
            return null;
        });
    }

    private static final class RecordingGateway implements OrgGateway {
        final java.util.Set<String> calls = new java.util.LinkedHashSet<>();

        @Override
        public List<SObject> query(String soql, Map<String, Object> binds) {
            return new List<>();
        }

        @Override
        public void insert(List<SObject> records) {
            calls.add("insert");
        }

        @Override
        public void update(List<SObject> records) {
            calls.add("update");
        }

        @Override
        public void delete(List<SObject> records) {
            calls.add("delete");
        }

        @Override
        public void upsert(List<SObject> records) {
            calls.add("upsert");
        }
    }
}
