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

    // --- Batch orchestration ------------------------------------------------------------------
    // executeBatch hands a Batchable to the async framework (chunking, scope iteration, retries) —
    // none of which exists locally — so it degrades clearly. The optional scope arg doesn't change
    // that. Returning a fake AsyncApexJob id would imply a job that never runs, so we don't.

    public static String executeBatch(Object batchable) {
        throw Unsupported.notLocal("Database.executeBatch()");
    }

    public static String executeBatch(Object batchable, Integer scope) {
        throw Unsupported.notLocal("Database.executeBatch()");
    }

    // --- Savepoint / rollback -----------------------------------------------------------------
    // DML hits the org through the gateway when connected. A local "rollback" that no-op'd would
    // LIE about the data state (the inserted/updated rows are already in the org), so rollback
    // degrades honestly. setSavepoint hands back an opaque token so the declared type resolves and
    // the savepoint variable round-trips through code, even though it carries no undoable state.

    /** Opaque transaction savepoint token (Apex {@code System.Savepoint}). Carries no local state. */
    public static final class Savepoint {
        private Savepoint() {
        }
    }

    public static Savepoint setSavepoint() {
        return new Savepoint();
    }

    public static void rollback(Savepoint savepoint) {
        // A local rollback can't undo rows the gateway already committed to the org — degrade
        // honestly rather than silently leave the data in a state the caller thinks was rolled back.
        throw Unsupported.notLocal("Database.rollback()");
    }

    // --- Lead conversion ----------------------------------------------------------------------
    // LeadConvert is pure data (setters store, getters read back), so domain code that builds one
    // compiles and round-trips. The conversion itself (creating Account/Contact/Opportunity and
    // flipping the lead) is org-bound, so convertLead degrades clearly. LeadConvertResult mirrors
    // the per-row outcome surface the way the DML results do.

    public static LeadConvertResult convertLead(LeadConvert leadToConvert) {
        throw Unsupported.notLocal("Database.convertLead()");
    }

    public static LeadConvertResult convertLead(LeadConvert leadToConvert, Boolean allOrNone) {
        throw Unsupported.notLocal("Database.convertLead()");
    }

    public static List<LeadConvertResult> convertLead(List<LeadConvert> leadsToConvert) {
        throw Unsupported.notLocal("Database.convertLead()");
    }

    /**
     * Input to {@code Database.convertLead(...)} (Database.LeadConvert). Pure data: the setters
     * store their value, the getters read it back, so domain code that populates one round-trips
     * locally. The conversion that consumes it is org-bound (see {@link #convertLead}).
     */
    public static final class LeadConvert {
        private String leadId;
        private String convertedStatus;
        private String accountId;
        private String contactId;
        private String opportunityName;
        private Boolean doNotCreateOpportunity;
        private Boolean overwriteLeadSource;
        private Boolean sendNotificationEmail;
        private String ownerId;

        public void setLeadId(String leadId) {
            this.leadId = leadId;
        }

        public String getLeadId() {
            return leadId;
        }

        public void setConvertedStatus(String convertedStatus) {
            this.convertedStatus = convertedStatus;
        }

        public String getConvertedStatus() {
            return convertedStatus;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setContactId(String contactId) {
            this.contactId = contactId;
        }

        public String getContactId() {
            return contactId;
        }

        public void setOpportunityName(String opportunityName) {
            this.opportunityName = opportunityName;
        }

        public String getOpportunityName() {
            return opportunityName;
        }

        public void setDoNotCreateOpportunity(Boolean doNotCreateOpportunity) {
            this.doNotCreateOpportunity = doNotCreateOpportunity;
        }

        public Boolean getDoNotCreateOpportunity() {
            return doNotCreateOpportunity;
        }

        public void setOverwriteLeadSource(Boolean overwriteLeadSource) {
            this.overwriteLeadSource = overwriteLeadSource;
        }

        public Boolean getOverwriteLeadSource() {
            return overwriteLeadSource;
        }

        public void setSendNotificationEmail(Boolean sendNotificationEmail) {
            this.sendNotificationEmail = sendNotificationEmail;
        }

        public Boolean getSendNotificationEmail() {
            return sendNotificationEmail;
        }

        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }

        public String getOwnerId() {
            return ownerId;
        }
    }

    /**
     * Per-row outcome of {@code Database.convertLead(...)} (Database.LeadConvertResult). The created
     * record ids are produced by the org conversion, which isn't local, so reading one fails clearly
     * (mirrors {@link DmlResult}). It exists so the declared return type resolves.
     */
    public static final class LeadConvertResult {
        public boolean isSuccess() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (isSuccess)");
        }

        public String getLeadId() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (getLeadId)");
        }

        public String getAccountId() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (getAccountId)");
        }

        public String getContactId() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (getContactId)");
        }

        public String getOpportunityId() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (getOpportunityId)");
        }

        public List<Error> getErrors() {
            throw Unsupported.notLocal("Database.LeadConvertResult inspection (getErrors)");
        }
    }

    // --- Aggregate count ----------------------------------------------------------------------
    // A COUNT() SOQL returns no records, only an aggregate totalSize the gateway's row-oriented
    // query() doesn't surface. Faking it by counting returned rows would be wrong (COUNT() returns
    // zero rows), so countQuery degrades clearly until the gateway models the aggregate response.

    public static Integer countQuery(String soql) {
        throw Unsupported.notLocal("Database.countQuery()");
    }

    public static Integer countQuery(String soql, java.util.Map<String, Object> binds) {
        throw Unsupported.notLocal("Database.countQuery()");
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

    // --- Batch Apex interfaces ----------------------------------------------------------------
    // A batch class declares `implements Database.Batchable<sObject>` plus optional markers
    // (Stateful/AllowsCallouts/RaisesPlatformEvents). These are real Apex platform interfaces,
    // so they map to real Java interfaces — otherwise javac sees a class where it expects an
    // interface ("interface expected here"). The method signatures mirror what the transpiler
    // EMITS for start/execute/finish: QueryLocator start(BatchableContext), void execute(
    // BatchableContext, List<T>), void finish(BatchableContext). Batch ORCHESTRATION isn't
    // modeled locally (chunking, scope iteration), only the type surface so domain code compiles.

    /**
     * Apex {@code Database.Batchable<T>}: a batch job's start/execute/finish contract. {@code T}
     * is the scope element type ({@code sObject} for query-locator batches). Implementations
     * compile and can be unit-tested directly; the framework that drives them isn't local.
     */
    public interface Batchable<T> {
        QueryLocator start(BatchableContext bc);

        void execute(BatchableContext bc, List<T> scope);

        void finish(BatchableContext bc);
    }

    /** Marker: a batch keeps instance state across execute() chunks (Database.Stateful). */
    public interface Stateful {
    }

    /** Marker: a batch may make callouts from execute() (Database.AllowsCallouts). */
    public interface AllowsCallouts {
    }

    /** Marker: a batch raises BatchApexErrorEvent platform events (Database.RaisesPlatformEvents). */
    public interface RaisesPlatformEvents {
    }

    /**
     * Handle passed to every batch callback (Database.BatchableContext). The job/child ids are
     * an org-runtime concern, so reading one fails clearly rather than returning a fake id.
     */
    public interface BatchableContext {
        default String getJobId() {
            throw Unsupported.notLocal("Database.BatchableContext.getJobId()");
        }

        default String getChildJobId() {
            throw Unsupported.notLocal("Database.BatchableContext.getChildJobId()");
        }
    }

    /**
     * Opaque cursor returned by {@code Database.getQueryLocator(...)} and a batch's start()
     * (Database.QueryLocator). Local has no streaming query cursor, so it carries no rows;
     * iterating one isn't modeled. It exists so start()'s declared return type resolves.
     */
    public interface QueryLocator {
        default java.util.Iterator<SObject> iterator() {
            throw Unsupported.notLocal("Database.QueryLocator iteration");
        }
    }

    /** Local has no query cursor; getQueryLocator is recognized but fails clearly if invoked. */
    public static QueryLocator getQueryLocator(String soql) {
        throw Unsupported.notLocal("Database.getQueryLocator");
    }
}
