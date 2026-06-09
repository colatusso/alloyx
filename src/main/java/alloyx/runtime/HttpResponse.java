package alloyx.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** Apex {@code System.HttpResponse} — holds the real response {@link Http} got back. */
public final class HttpResponse {
    private String body = "";
    private int statusCode;
    private String status = "";
    private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

    public String getBody() {
        return body;
    }

    public Blob getBodyAsBlob() {
        return Blob.of(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    public List<String> getHeaderKeys() {
        List<String> keys = new List<>();
        for (String k : headers.keySet()) {
            keys.add(k);
        }
        return keys;
    }

    public String getStatus() {
        return status;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }

    public void setBodyAsBlob(Blob body) {
        this.body = body == null ? "" : body.toString();
    }

    public void setHeader(String key, String value) {
        headers.put(key, value);
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode == null ? 0 : statusCode;
    }
}
