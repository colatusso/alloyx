package alloyx.runtime;

/**
 * Static helpers for Apex's {@code String} class.
 *
 * <p>Apex's {@code String} exposes static methods (e.g. {@code String.isBlank},
 * {@code String.join}, {@code String.format}) that {@link java.lang.String} either
 * lacks or implements with different semantics. The transpiler routes
 * {@code String.xxx(...)} static calls here. Instance methods stay on the real
 * {@code java.lang.String} and are not duplicated.
 *
 * <p>Named "Strings" (not "String") to avoid shadowing {@code java.lang.String},
 * which is needed everywhere as the actual character-sequence type. All methods
 * are {@code public static}.
 */
public class Strings {

    private Strings() {
        // static-only utility class
    }

    /** Apex {@code String.isBlank}: true if null, empty, or whitespace-only. */
    public static Boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Apex {@code String.isNotBlank}: inverse of {@link #isBlank}. */
    public static Boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /** Apex {@code String.isEmpty}: true if null or length 0 (whitespace is NOT empty). */
    public static Boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** Apex {@code String.isNotEmpty}: inverse of {@link #isEmpty}. */
    public static Boolean isNotEmpty(String s) {
        return !isEmpty(s);
    }

    /**
     * Apex {@code String.valueOf(Object)}: string representation of any object.
     * A null argument yields the literal text {@code "null"} (matching both Apex
     * and {@link java.lang.String#valueOf(Object)}).
     */
    public static String valueOf(Object o) {
        return String.valueOf(o);
    }

    /**
     * Apex {@code String.join(Iterable, separator)}: concatenates each element's
     * string value, placing {@code separator} between consecutive elements.
     */
    public static String join(Iterable<?> items, String separator) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(String.valueOf(item));
            first = false;
        }
        return sb.toString();
    }

    /** Apex {@code String.join(Object[], separator)}: array overload of {@link #join(Iterable, String)}. */
    public static String join(Object[] items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(String.valueOf(items[i]));
        }
        return sb.toString();
    }

    /**
     * Apex {@code String.format(template, args)}.
     *
     * <p>Apex uses MessageFormat-style positional placeholders {@code {0} {1} ...}
     * (NOT Java's {@code %s}). We substitute manually rather than delegating to
     * {@link java.text.MessageFormat} on purpose: MessageFormat applies its own
     * quoting rules (single quotes escape/suppress placeholders, {@code ''} becomes
     * a literal quote, and it parses number/date sub-formats), none of which match
     * Apex's plain index-substitution behavior. Manual replacement keeps the output
     * faithful to Apex and avoids surprising quote handling.
     *
     * <p>A placeholder {@code {n}} is replaced by {@code String.valueOf(args.get(n))}.
     * Indexes outside the args range are left untouched (the literal {@code {n}} stays).
     */
    public static String format(String template, java.util.List<?> args) {
        if (template == null) {
            return null;
        }
        if (args == null || args.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int len = template.length();
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i + 1) {
                    String inner = template.substring(i + 1, close);
                    Integer index = parseIndex(inner);
                    if (index != null && index >= 0 && index < args.size()) {
                        out.append(String.valueOf(args.get(index)));
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Parses a non-negative decimal placeholder index, or null if not a plain integer. */
    private static Integer parseIndex(String text) {
        if (text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Apex {@code String.escapeSingleQuotes}: prefixes each single quote with a
     * backslash (used to guard against SOQL/SOSL injection). A null input returns null.
     */
    public static String escapeSingleQuotes(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("'", "\\'");
    }
}
