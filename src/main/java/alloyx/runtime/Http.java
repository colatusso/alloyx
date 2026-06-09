package alloyx.runtime;

/** Apex {@code System.Http} — recognized for type-checking; callouts don't run locally yet. */
public final class Http {
    public HttpResponse send(HttpRequest request) {
        throw Unsupported.notLocal("Http.send");
    }
}
