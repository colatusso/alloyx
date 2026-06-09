package alloyx.runtime;

/** Apex {@code Blob} — recognized for type-checking; not produced/consumed locally yet. */
public final class Blob {
    public static Blob valueOf(String value) {
        throw Unsupported.notLocal("Blob.valueOf");
    }

    public Integer size() {
        throw Unsupported.notLocal("Blob.size");
    }

    @Override
    public String toString() {
        throw Unsupported.notLocal("Blob.toString");
    }
}
