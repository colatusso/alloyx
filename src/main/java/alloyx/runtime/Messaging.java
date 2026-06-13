// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Messaging} namespace — outbound email composition.
 *
 * <p>Composing a message is pure DATA building, so {@link SingleEmailMessage} and
 * {@link EmailFileAttachment} run LOCALLY: their setters store and the getters round-trip,
 * exactly as the platform's value-object semantics. SENDING is an org/transport concern,
 * so {@link #sendEmail} degrades clearly ({@link Unsupported#notLocal}) rather than
 * pretending to deliver. The per-message {@link SendEmailResult}/{@link SendEmailError}
 * mirror the Database DML-result shape: recognized for type-checking, inspection degrades.
 *
 * <p>Inbound email ({@code Messaging.InboundEmail} and friends) is intentionally NOT modeled
 * — it's an org-runtime entry point with no local driver and isn't referenced by the corpus.
 */
public final class Messaging {
    private Messaging() {}

    /** Send composed emails. Delivery needs the org's mail transport — not available locally. */
    public static List<SendEmailResult> sendEmail(Object emails) {
        throw Unsupported.notLocal("Messaging.sendEmail(...)");
    }

    public static List<SendEmailResult> sendEmail(Object emails, Object allOrNone) {
        throw Unsupported.notLocal("Messaging.sendEmail(...)");
    }

    /** Render an email template against a target/what — org metadata; not available locally. */
    public static SingleEmailMessage renderStoredEmailTemplate(
            String templateId, String targetObjectId, String whatId) {
        throw Unsupported.notLocal("Messaging.renderStoredEmailTemplate(...)");
    }

    /**
     * Apex {@code Messaging.SingleEmailMessage} — a composable outbound email. Every setter
     * stores its value and the paired getter returns it, so building/inspecting a message runs
     * locally and round-trips. Only sending (above) is org-bound.
     */
    public static final class SingleEmailMessage {
        private List<String> toAddresses;
        private List<String> ccAddresses;
        private List<String> bccAddresses;
        private String subject;
        private String plainTextBody;
        private String htmlBody;
        private List<EmailFileAttachment> fileAttachments;
        private Boolean saveAsActivity;
        private Boolean bccSender;
        private Boolean useSignature;
        private String targetObjectId;
        private String whatId;
        private String orgWideEmailAddressId;
        private List<String> replyTo = new List<>();
        private String senderDisplayName;
        private String templateId;
        private List<String> ccAddressesList;
        private String charset;
        private String inReplyTo;
        private String references;
        private Boolean treatTargetObjectAsRecipient;
        private List<String> entityAttachments;

        public SingleEmailMessage() {
        }

        public void setToAddresses(List<String> toAddresses) {
            this.toAddresses = toAddresses;
        }

        public List<String> getToAddresses() {
            return toAddresses;
        }

        public void setCcAddresses(List<String> ccAddresses) {
            this.ccAddresses = ccAddresses;
        }

        public List<String> getCcAddresses() {
            return ccAddresses;
        }

        public void setBccAddresses(List<String> bccAddresses) {
            this.bccAddresses = bccAddresses;
        }

        public List<String> getBccAddresses() {
            return bccAddresses;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getSubject() {
            return subject;
        }

        public void setPlainTextBody(String plainTextBody) {
            this.plainTextBody = plainTextBody;
        }

        public String getPlainTextBody() {
            return plainTextBody;
        }

        public void setHtmlBody(String htmlBody) {
            this.htmlBody = htmlBody;
        }

        public String getHtmlBody() {
            return htmlBody;
        }

        public void setFileAttachments(List<EmailFileAttachment> fileAttachments) {
            this.fileAttachments = fileAttachments;
        }

        public List<EmailFileAttachment> getFileAttachments() {
            return fileAttachments;
        }

        public void setSaveAsActivity(Boolean saveAsActivity) {
            this.saveAsActivity = saveAsActivity;
        }

        public Boolean getSaveAsActivity() {
            return saveAsActivity;
        }

        public void setBccSender(Boolean bccSender) {
            this.bccSender = bccSender;
        }

        public Boolean getBccSender() {
            return bccSender;
        }

        public void setUseSignature(Boolean useSignature) {
            this.useSignature = useSignature;
        }

        public Boolean getUseSignature() {
            return useSignature;
        }

        public void setTargetObjectId(String targetObjectId) {
            this.targetObjectId = targetObjectId;
        }

        public String getTargetObjectId() {
            return targetObjectId;
        }

        public void setWhatId(String whatId) {
            this.whatId = whatId;
        }

        public String getWhatId() {
            return whatId;
        }

        public void setOrgWideEmailAddressId(String orgWideEmailAddressId) {
            this.orgWideEmailAddressId = orgWideEmailAddressId;
        }

        public String getOrgWideEmailAddressId() {
            return orgWideEmailAddressId;
        }

        public void setReplyTo(String replyTo) {
            this.replyTo = new List<>();
            this.replyTo.add(replyTo);
        }

        public String getReplyTo() {
            return replyTo == null || replyTo.isEmpty() ? null : replyTo.get(0);
        }

        public void setSenderDisplayName(String senderDisplayName) {
            this.senderDisplayName = senderDisplayName;
        }

        public String getSenderDisplayName() {
            return senderDisplayName;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setCharset(String charset) {
            this.charset = charset;
        }

        public String getCharset() {
            return charset;
        }

        public void setInReplyTo(String inReplyTo) {
            this.inReplyTo = inReplyTo;
        }

        public String getInReplyTo() {
            return inReplyTo;
        }

        public void setReferences(String references) {
            this.references = references;
        }

        public String getReferences() {
            return references;
        }

        public void setTreatTargetObjectAsRecipient(Boolean treatTargetObjectAsRecipient) {
            this.treatTargetObjectAsRecipient = treatTargetObjectAsRecipient;
        }

        public Boolean getTreatTargetObjectAsRecipient() {
            return treatTargetObjectAsRecipient;
        }

        public void setEntityAttachments(List<String> entityAttachments) {
            this.entityAttachments = entityAttachments;
        }

        public List<String> getEntityAttachments() {
            return entityAttachments;
        }
    }

    /**
     * Apex {@code Messaging.EmailFileAttachment} — a single attachment. Pure value object:
     * setters store, getters return.
     */
    public static final class EmailFileAttachment {
        private String fileName;
        private Blob body;
        private String contentType;
        private Boolean inline;
        private String id;

        public EmailFileAttachment() {
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }

        public void setBody(Blob body) {
            this.body = body;
        }

        public Blob getBody() {
            return body;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getContentType() {
            return contentType;
        }

        public void setInline(Boolean inline) {
            this.inline = inline;
        }

        public Boolean getInline() {
            return inline;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /**
     * Apex {@code Messaging.SendEmailResult} — the per-message send outcome. Sending isn't local,
     * so a result is never produced locally; inspecting one degrades clearly (mirrors the Database
     * DML-result shape). Recognized so {@code Messaging.SendEmailResult[] r = ...} type-checks.
     */
    public static final class SendEmailResult {
        public boolean isSuccess() {
            throw Unsupported.notLocal("Messaging.SendEmailResult.isSuccess()");
        }

        public List<SendEmailError> getErrors() {
            throw Unsupported.notLocal("Messaging.SendEmailResult.getErrors()");
        }
    }

    /** Apex {@code Messaging.SendEmailError} — one error on a send result. Inspection degrades. */
    public static final class SendEmailError {
        public String getMessage() {
            throw Unsupported.notLocal("Messaging.SendEmailError.getMessage()");
        }

        public String getStatusCode() {
            throw Unsupported.notLocal("Messaging.SendEmailError.getStatusCode()");
        }

        public String getTargetObjectId() {
            throw Unsupported.notLocal("Messaging.SendEmailError.getTargetObjectId()");
        }

        public List<String> getFields() {
            throw Unsupported.notLocal("Messaging.SendEmailError.getFields()");
        }
    }
}
