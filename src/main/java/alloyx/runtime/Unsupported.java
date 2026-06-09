package alloyx.runtime;

/**
 * Native Apex APIs that AlloyX recognizes so code type-checks, but does not run
 * locally yet (callouts, HTTP, XML I/O). The type surface lives in the runtime so
 * the compiler resolves it; calling one fails clearly instead of silently. The
 * behavior can be filled in later without touching any caller.
 */
public final class Unsupported {
    private Unsupported() {}

    public static UnsupportedOperationException notLocal(String what) {
        return new UnsupportedOperationException(
            what + " is not available locally yet — AlloyX runs domain logic, not callouts/HTTP/XML");
    }
}
