// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** Apex {@code System.HttpRequest} — holds the outbound request; sent for real by {@link Http}. */
public final class HttpRequest {
    private String endpoint;
    private String method = "GET";
    private String body = "";
    private int timeoutMs = 0;
    private boolean compressed = false;
    private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

    public HttpRequest() {
    }

    public String getBody() {
        return body;
    }

    public Blob getBodyAsBlob() {
        return Blob.of(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    public Boolean getCompressed() {
        return compressed;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    public String getMethod() {
        return method;
    }

    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }

    public void setBodyAsBlob(Blob body) {
        this.body = body == null ? "" : body.toString();
    }

    public void setClientCertificate(String clientCert, String password) {
        // client certificates aren't applied locally; accepted so code type-checks and runs
    }

    public void setClientCertificateName(String certDevName) {
        // see setClientCertificate
    }

    public void setCompressed(Boolean compressed) {
        this.compressed = compressed != null && compressed;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setHeader(String key, String value) {
        headers.put(key, value);
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setTimeout(Integer timeout) {
        this.timeoutMs = timeout == null ? 0 : timeout;
    }

    // --- package-internal accessors for Http.send ---
    LinkedHashMap<String, String> headerMap() {
        return headers;
    }

    int timeoutMs() {
        return timeoutMs;
    }
}
