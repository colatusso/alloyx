// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code System.Pattern} — regular expressions, backed by
 * {@link java.util.regex.Pattern}. {@code Pattern.compile(regex)} builds a pattern
 * and {@code matcher(input)} a {@link Matcher}; {@code Pattern.matches(regex, input)}
 * is the one-shot whole-string test.
 */
public final class Pattern {
    private final java.util.regex.Pattern impl;

    private Pattern(java.util.regex.Pattern impl) {
        this.impl = impl;
    }

    public static Pattern compile(String regex) {
        return new Pattern(java.util.regex.Pattern.compile(regex));
    }

    public static Boolean matches(String regex, String input) {
        return java.util.regex.Pattern.matches(regex, input);
    }

    public Matcher matcher(String input) {
        return new Matcher(impl.matcher(input));
    }

    public String pattern() {
        return impl.pattern();
    }
}
