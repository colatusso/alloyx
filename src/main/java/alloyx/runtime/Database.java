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

    // DML returns the processed records (Apex returns Database.*Result[]; locally we have
    // no real SaveResult, so we hand back the records, which satisfies `return Database.x(...)`
    // and `List<Database.SaveResult> r = ...`). The extra arg (allOrNone / DmlOptions /
    // external-id field) doesn't change local behavior — there's no partial-success engine.
    public static List<SObject> insert(Object records) {
        List<SObject> r = asList(records);
        gateway.insert(r);
        return r;
    }

    public static List<SObject> insert(Object records, Object allOrNoneOrOptions) {
        return insert(records);
    }

    public static List<SObject> update(Object records) {
        List<SObject> r = asList(records);
        gateway.update(r);
        return r;
    }

    public static List<SObject> update(Object records, Object allOrNoneOrOptions) {
        return update(records);
    }

    public static List<SObject> delete(Object records) {
        List<SObject> r = asList(records);
        gateway.delete(r);
        return r;
    }

    public static List<SObject> delete(Object records, Object allOrNoneOrOptions) {
        return delete(records);
    }

    public static List<SObject> upsert(Object records) {
        List<SObject> r = asList(records);
        gateway.upsert(r);
        return r;
    }

    public static List<SObject> upsert(Object records, Object externalIdField) {
        return upsert(records);
    }

    public static List<SObject> upsert(Object records, Object externalIdField, Object allOrNone) {
        return upsert(records);
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
}
