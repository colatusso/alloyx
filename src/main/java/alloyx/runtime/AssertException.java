package alloyx.runtime;

/** Raised when a System.assert* check fails (mirrors Apex assert failures). */
public class AssertException extends RuntimeException {
    public AssertException(String message) {
        super(message);
    }
}
