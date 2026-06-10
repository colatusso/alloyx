// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code System.LoggingLevel} — the severity passed to {@code System.debug}.
 * Modelled as named constants (Apex's enum) so {@code LoggingLevel.INFO} etc. resolve.
 */
public final class LoggingLevel {
    public static final LoggingLevel NONE = new LoggingLevel("NONE");
    public static final LoggingLevel ERROR = new LoggingLevel("ERROR");
    public static final LoggingLevel WARN = new LoggingLevel("WARN");
    public static final LoggingLevel INFO = new LoggingLevel("INFO");
    public static final LoggingLevel DEBUG = new LoggingLevel("DEBUG");
    public static final LoggingLevel FINE = new LoggingLevel("FINE");
    public static final LoggingLevel FINER = new LoggingLevel("FINER");
    public static final LoggingLevel FINEST = new LoggingLevel("FINEST");

    private final String label;

    private LoggingLevel(String label) {
        this.label = label;
    }

    public String name() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
