// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code PageReference} — a Visualforce page/URL handle. The URL and query
 * parameters are pure data, so they round-trip locally (build a PageReference, read
 * its url/params back). Anything org-coupled — rendering a page to content/PDF —
 * has no local equivalent and fails clearly.
 */
public final class PageReference {
    private String url;
    private final java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();

    public PageReference(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    /** Mutable query-parameter map (Apex callers add to it in place). */
    public java.util.Map<String, String> getParameters() {
        return parameters;
    }

    /** Apex returns the same PageReference for chaining. The redirect flag has no local effect. */
    public PageReference setRedirect(Boolean redirect) {
        return this;
    }

    public Blob getContent() {
        throw Unsupported.notLocal("PageReference.getContent()");
    }

    public Blob getContentAsPDF() {
        throw Unsupported.notLocal("PageReference.getContentAsPDF()");
    }
}
