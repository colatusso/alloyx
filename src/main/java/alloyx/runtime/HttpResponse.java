package alloyx.runtime;

/** Apex {@code System.HttpResponse} — recognized for type-checking; callouts don't run locally yet. */
public final class HttpResponse {
    public String getBody() {
        throw Unsupported.notLocal("HttpResponse.getBody");
    }

    public Blob getBodyAsBlob() {
        throw Unsupported.notLocal("HttpResponse.getBodyAsBlob");
    }

    public String getHeader(String key) {
        throw Unsupported.notLocal("HttpResponse.getHeader");
    }

    public List<String> getHeaderKeys() {
        throw Unsupported.notLocal("HttpResponse.getHeaderKeys");
    }

    public String getStatus() {
        throw Unsupported.notLocal("HttpResponse.getStatus");
    }

    public Integer getStatusCode() {
        throw Unsupported.notLocal("HttpResponse.getStatusCode");
    }

    public void setBody(String body) {
        throw Unsupported.notLocal("HttpResponse.setBody");
    }

    public void setBodyAsBlob(Blob body) {
        throw Unsupported.notLocal("HttpResponse.setBodyAsBlob");
    }

    public void setHeader(String key, String value) {
        throw Unsupported.notLocal("HttpResponse.setHeader");
    }

    public void setStatus(String status) {
        throw Unsupported.notLocal("HttpResponse.setStatus");
    }

    public void setStatusCode(Integer statusCode) {
        throw Unsupported.notLocal("HttpResponse.setStatusCode");
    }
}
