// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code ApexPages} — the Visualforce page-message API. Recognized so controller
 * code type-checks. {@link Severity} and {@link Message} are pure data and round-trip
 * locally (a Message stores its severity/summary/detail and reads them back, like a
 * record). The page-message statics (addMessage/getMessages/hasMessages) collect into a
 * process-static buffer so controller logic that posts a message and reads it back works
 * locally, mirroring how {@link Message} already round-trips. Truly page-bound state — a
 * live PageReference, a saved record — has no local equivalent, so those degrade clearly.
 */
public final class ApexPages {
    private ApexPages() {}

    // Process-static message buffer. There's no per-request page context locally, so messages
    // accumulate here for the duration of the run; clearMessages() resets it (tests isolate via it).
    private static final List<Message> MESSAGES = new List<>();

    // Process-static current page. There's no live Visualforce request locally, so the "current page"
    // is whatever Test.setCurrentPage last installed (mirroring how Apex tests seed the page context);
    // null means none was set, and currentPage() then stands in an empty PageReference.
    private static PageReference currentPage;

    public static void addMessage(Object message) {
        MESSAGES.add((Message) message);
    }

    public static void addMessages(Object exception) {
        // A whole-exception add (Apex maps a DmlException's row errors to messages). Locally we don't
        // model per-row DML errors, so record one ERROR message carrying the exception's text.
        MESSAGES.add(new Message(Severity.ERROR, String.valueOf(exception)));
    }

    public static List<Message> getMessages() {
        return MESSAGES;
    }

    public static boolean hasMessages() {
        return !MESSAGES.isEmpty();
    }

    public static boolean hasMessages(Severity severity) {
        for (Message m : MESSAGES) {
            if (m.getSeverity() == severity) {
                return true;
            }
        }
        return false;
    }

    /** Reset the local message buffer. Not an Apex API — a local-run/test isolation helper. */
    public static void clearMessages() {
        MESSAGES.clear();
    }

    /** Apex {@code ApexPages.currentPage()}: returns the page installed via
     *  {@link #setCurrentPage(PageReference)} (i.e. {@code Test.setCurrentPage(...)}). With no page
     *  set there's no Visualforce request locally, so an empty PageReference (no params) stands in —
     *  readable like the real one, just unpopulated. */
    public static PageReference currentPage() {
        return currentPage != null ? currentPage : new PageReference("");
    }

    /** Install the process-static current page (Apex tests seed it via {@code Test.setCurrentPage}).
     *  A null clears it, so {@link #currentPage()} falls back to the empty stand-in. Not a page-bound
     *  org operation — pure local state — so it runs for real, keeping the two consistent. */
    public static void setCurrentPage(PageReference page) {
        currentPage = page;
    }

    /**
     * Message severity. Modelled as named constants (Apex's enum) so
     * {@code ApexPages.Severity.ERROR} etc. resolve. Case-insensitive member access is
     * folded to these UPPER_CASE constants by the transpiler, like other native enums.
     */
    public enum Severity {
        CONFIRM, ERROR, FATAL, INFO, WARNING
    }

    /**
     * A page message. Pure data: stores severity/summary/detail and returns them — trivially
     * correct locally, no org context needed.
     */
    public static final class Message {
        private final Severity severity;
        private final String summary;
        private final String detail;

        public Message(Severity severity, String summary) {
            this(severity, summary, null);
        }

        public Message(Severity severity, String summary, String detail) {
            this.severity = severity;
            this.summary = summary;
            this.detail = detail;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getSummary() {
            return summary;
        }

        public String getDetail() {
            return detail;
        }
    }

    /**
     * Apex {@code ApexPages.StandardController} — the single-record controller a Visualforce
     * standard page binds to. The record it wraps is pure data and round-trips (getRecord/getId);
     * the page-bound operations (save/edit/cancel/the navigation PageReferences) need a live page
     * and an org transaction, so they degrade clearly.
     */
    public static class StandardController {
        private final SObject record;

        public StandardController(SObject record) {
            this.record = record;
        }

        public SObject getRecord() {
            return record;
        }

        public String getId() {
            return record == null ? null : (String) record.get("Id");
        }

        public void addFields(List<String> fieldNames) {
            // Apex pre-loads these fields for the page; locally the record already carries whatever
            // was set, so there's nothing to fetch — a no-op (not a degradation).
        }

        public PageReference save() {
            throw Unsupported.notLocal("ApexPages.StandardController.save()");
        }

        public PageReference edit() {
            throw Unsupported.notLocal("ApexPages.StandardController.edit()");
        }

        public PageReference delete() {
            throw Unsupported.notLocal("ApexPages.StandardController.delete()");
        }

        public PageReference cancel() {
            throw Unsupported.notLocal("ApexPages.StandardController.cancel()");
        }

        public PageReference view() {
            throw Unsupported.notLocal("ApexPages.StandardController.view()");
        }
    }

    /**
     * Apex {@code ApexPages.StandardSetController} — the list controller a Visualforce standard
     * list page binds to. The wrapped records round-trip (getRecords/getSelected/selection); the
     * paging and DML-bound operations (save/next/previous) need a live page/org and degrade clearly.
     */
    public static class StandardSetController {
        private List<SObject> records;
        private List<SObject> selected = new List<>();
        private Integer pageSize = 20;

        public StandardSetController(List<SObject> records) {
            this.records = records;
        }

        public StandardSetController(Database.QueryLocator locator) {
            // A query-locator-backed set controller streams from the org; local has no cursor.
            throw Unsupported.notLocal("ApexPages.StandardSetController(QueryLocator)");
        }

        public List<SObject> getRecords() {
            return records;
        }

        public List<SObject> getSelected() {
            return selected;
        }

        public void setSelected(List<SObject> selected) {
            this.selected = selected;
        }

        public Integer getPageSize() {
            return pageSize;
        }

        public void setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
        }

        public Integer getRecordsSize() {
            return records == null ? 0 : records.size();
        }

        public PageReference save() {
            throw Unsupported.notLocal("ApexPages.StandardSetController.save()");
        }

        public void next() {
            throw Unsupported.notLocal("ApexPages.StandardSetController.next()");
        }

        public void previous() {
            throw Unsupported.notLocal("ApexPages.StandardSetController.previous()");
        }
    }
}
