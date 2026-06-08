package alloyx.runtime;

/**
 * Apex Exception base. Unchecked (extends RuntimeException) so transpiled method
 * signatures don't need {@code throws}. Custom Apex exceptions transpile to
 * subclasses of this, and platform exceptions (DmlException, QueryException, …)
 * collapse onto it. Exposes the Apex exception methods used in practice.
 */
public class ApexException extends RuntimeException {
    public ApexException() {
        super();
    }

    public ApexException(String message) {
        super(message);
    }

    public String getTypeName() {
        return getClass().getSimpleName();
    }

    public String getStackTraceString() {
        return "";
    }

    /** Apex {@code Exception.getLineNumber()}: no Apex line table locally, so 0. */
    public Integer getLineNumber() {
        return 0;
    }
}
