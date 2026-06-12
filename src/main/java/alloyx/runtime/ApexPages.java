// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code ApexPages} — the Visualforce page-message API. Recognized so controller
 * code type-checks. {@link Severity} and {@link Message} are pure data and round-trip
 * locally (a Message stores its severity/summary/detail and reads them back, like a
 * record). The page-bound statics (addMessage/getMessages/...) have no local page
 * context, so they fail clearly rather than silently swallowing messages.
 */
public final class ApexPages {
    private ApexPages() {}

    public static void addMessage(Object message) {
        throw Unsupported.notLocal("ApexPages.addMessage()");
    }

    public static void addMessages(Object exception) {
        throw Unsupported.notLocal("ApexPages.addMessages()");
    }

    public static List<Message> getMessages() {
        throw Unsupported.notLocal("ApexPages.getMessages()");
    }

    public static boolean hasMessages() {
        throw Unsupported.notLocal("ApexPages.hasMessages()");
    }

    public static boolean hasMessages(Severity severity) {
        throw Unsupported.notLocal("ApexPages.hasMessages(Severity)");
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
}
