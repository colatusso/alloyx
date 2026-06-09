package alloyx.runtime;

/** Apex {@code System.HttpRequest} — recognized for type-checking; callouts don't run locally yet. */
public final class HttpRequest {
    public HttpRequest() {
    }

    public String getBody() {
        throw Unsupported.notLocal("HttpRequest.getBody");
    }

    public Blob getBodyAsBlob() {
        throw Unsupported.notLocal("HttpRequest.getBodyAsBlob");
    }

    public Boolean getCompressed() {
        throw Unsupported.notLocal("HttpRequest.getCompressed");
    }

    public String getEndpoint() {
        throw Unsupported.notLocal("HttpRequest.getEndpoint");
    }

    public String getHeader(String key) {
        throw Unsupported.notLocal("HttpRequest.getHeader");
    }

    public String getMethod() {
        throw Unsupported.notLocal("HttpRequest.getMethod");
    }

    public void setBody(String body) {
        throw Unsupported.notLocal("HttpRequest.setBody");
    }

    public void setBodyAsBlob(Blob body) {
        throw Unsupported.notLocal("HttpRequest.setBodyAsBlob");
    }

    public void setClientCertificate(String clientCert, String password) {
        throw Unsupported.notLocal("HttpRequest.setClientCertificate");
    }

    public void setClientCertificateName(String certDevName) {
        throw Unsupported.notLocal("HttpRequest.setClientCertificateName");
    }

    public void setCompressed(Boolean compressed) {
        throw Unsupported.notLocal("HttpRequest.setCompressed");
    }

    public void setEndpoint(String endpoint) {
        throw Unsupported.notLocal("HttpRequest.setEndpoint");
    }

    public void setHeader(String key, String value) {
        throw Unsupported.notLocal("HttpRequest.setHeader");
    }

    public void setMethod(String method) {
        throw Unsupported.notLocal("HttpRequest.setMethod");
    }

    public void setTimeout(Integer timeout) {
        throw Unsupported.notLocal("HttpRequest.setTimeout");
    }
}
