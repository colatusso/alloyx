package alloyx.runtime;

/**
 * Apex {@code System.Limits} — governor-limit counters. None of the platform's
 * limits apply when running locally on the JVM, so the "used" counters report 0
 * and the ceilings report the platform's documented synchronous maximums. This
 * lets limit-aware code compile and run; it just never trips a local limit.
 */
public final class Limits {
    private Limits() {
    }

    public static Integer getCpuTime() {
        return 0;
    }

    public static Integer getLimitCpuTime() {
        return 10000;
    }

    public static Integer getQueries() {
        return 0;
    }

    public static Integer getLimitQueries() {
        return 100;
    }

    public static Integer getQueryRows() {
        return 0;
    }

    public static Integer getLimitQueryRows() {
        return 50000;
    }

    public static Integer getDmlStatements() {
        return 0;
    }

    public static Integer getLimitDmlStatements() {
        return 150;
    }

    public static Integer getDmlRows() {
        return 0;
    }

    public static Integer getLimitDmlRows() {
        return 10000;
    }

    public static Integer getHeapSize() {
        return 0;
    }

    public static Integer getLimitHeapSize() {
        return 6000000;
    }

    public static Integer getCallouts() {
        return 0;
    }

    public static Integer getLimitCallouts() {
        return 100;
    }
}
