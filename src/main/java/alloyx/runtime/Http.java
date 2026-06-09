package alloyx.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Apex {@code System.Http} — performs the callout for real via the JDK HttpClient. No
 * Salesforce in the loop: the request goes straight to the endpoint, locally.
 */
public final class Http {
    public HttpResponse send(HttpRequest request) {
        int timeout = request.timeoutMs() > 0 ? request.timeoutMs() : 120_000;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

            String body = request.getBody();
            java.net.http.HttpRequest.BodyPublisher publisher = (body == null || body.isEmpty())
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(java.util.Locale.ROOT);

            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.getEndpoint()))
                .timeout(Duration.ofMillis(timeout))
                .method(method, publisher);
            request.headerMap().forEach((k, v) -> {
                try {
                    builder.header(k, v);
                } catch (IllegalArgumentException restricted) {
                    // the JDK forbids a few headers (Host, Content-Length, ...); skip those
                }
            });

            java.net.http.HttpResponse<String> resp = client.send(
                builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            HttpResponse out = new HttpResponse();
            out.setStatusCode(resp.statusCode());
            out.setBody(resp.body());
            resp.headers().map().forEach((k, values) -> {
                if (!values.isEmpty()) {
                    out.setHeader(k, values.get(0));
                }
            });
            return out;
        } catch (Exception e) {
            // Apex surfaces callout failures as System.CalloutException -> ApexException here
            throw new ApexException("Callout failed: " + e.getMessage());
        }
    }
}
