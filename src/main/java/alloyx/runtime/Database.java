// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Routes every org-bound operation through the current gateway. The gateway is a
 * single shared static, so all classes in a run target the same org.
 */
public final class Database {
    private static OrgGateway gateway = new UnconnectedGateway();

    private Database() {
    }

    public static void setGateway(OrgGateway g) {
        gateway = g;
    }

    public static OrgGateway gateway() {
        return gateway;
    }

    public static List<SObject> query(String soql, java.util.Map<String, Object> binds) {
        return gateway.query(soql, binds);
    }

    /** Native dynamic SOQL: Database.query(String) with no bind map. */
    public static List<SObject> query(String soql) {
        return gateway.query(soql, java.util.Map.of());
    }

    // Apex DML returns per-row result objects (Database.SaveResult[] and friends). The records
    // are processed through the gateway; the per-row outcome isn't modeled locally yet, so the
    // results are recognized for type-checking but inspecting one fails clearly (see DmlResult).
    // The extra arg (allOrNone / DmlOptions / external-id field) doesn't change local behavior.
    public static List<SaveResult> insert(Object records) {
        List<SObject> r = asList(records);
        gateway.insert(r);
        return filled(r.size(), SaveResult::new);
    }

    public static List<SaveResult> insert(Object records, Object allOrNoneOrOptions) {
        return insert(records);
    }

    public static List<SaveResult> update(Object records) {
        List<SObject> r = asList(records);
        gateway.update(r);
        return filled(r.size(), SaveResult::new);
    }

    public static List<SaveResult> update(Object records, Object allOrNoneOrOptions) {
        return update(records);
    }

    public static List<DeleteResult> delete(Object records) {
        List<SObject> r = asList(records);
        gateway.delete(r);
        return filled(r.size(), DeleteResult::new);
    }

    public static List<DeleteResult> delete(Object records, Object allOrNoneOrOptions) {
        return delete(records);
    }

    public static List<UpsertResult> upsert(Object records) {
        List<SObject> r = asList(records);
        gateway.upsert(r);
        return filled(r.size(), UpsertResult::new);
    }

    public static List<UpsertResult> upsert(Object records, Object externalIdField) {
        return upsert(records);
    }

    public static List<UpsertResult> upsert(Object records, Object externalIdField, Object allOrNone) {
        return upsert(records);
    }

    private static <T> List<T> filled(int n, java.util.function.Supplier<T> make) {
        List<T> out = new List<>();
        for (int i = 0; i < n; i++) out.add(make.get());
        return out;
    }

    /** Local has no transaction boundary; savepoint/rollback are stubs. */
    public static Object setSavepoint() {
        return null;
    }

    public static void rollback(Object savepoint) {
        // no-op locally
    }

    @SuppressWarnings("unchecked")
    private static List<SObject> asList(Object records) {
        if (records instanceof List) {
            return (List<SObject>) records;
        }
        List<SObject> one = new List<>();
        one.add((SObject) records);
        return one;
    }

    /**
     * Per-row DML outcome (Database.SaveResult / UpsertResult / DeleteResult). The shared
     * accessors are recognized so error-handling code type-checks; the local run processes the
     * records but doesn't model per-row success/errors yet, so reading one fails clearly.
     */
    public abstract static class DmlResult {
        public boolean isSuccess() {
            throw Unsupported.notLocal("Database result inspection (isSuccess)");
        }

        public String getId() {
            throw Unsupported.notLocal("Database result inspection (getId)");
        }

        public List<Error> getErrors() {
            throw Unsupported.notLocal("Database result inspection (getErrors)");
        }
    }

    public static final class SaveResult extends DmlResult {
    }

    public static final class DeleteResult extends DmlResult {
    }

    public static final class UpsertResult extends DmlResult {
        public boolean isCreated() {
            throw Unsupported.notLocal("Database result inspection (isCreated)");
        }
    }

    /** A single error attached to a DML result (Database.Error). */
    public static final class Error {
        public String getMessage() {
            throw Unsupported.notLocal("Database.Error.getMessage()");
        }

        public String getStatusCode() {
            throw Unsupported.notLocal("Database.Error.getStatusCode()");
        }

        public List<String> getFields() {
            throw Unsupported.notLocal("Database.Error.getFields()");
        }
    }
}
