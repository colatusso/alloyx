package alloyx.runtime;

/**
 * Apex {@code System.Matcher} — the result of {@link Pattern#matcher(String)},
 * backed by {@link java.util.regex.Matcher}. Drives a match and exposes the groups.
 */
public final class Matcher {
    private final java.util.regex.Matcher impl;

    Matcher(java.util.regex.Matcher impl) {
        this.impl = impl;
    }

    public Boolean matches() {
        return impl.matches();
    }

    public Boolean find() {
        return impl.find();
    }

    public Boolean find(Integer start) {
        return impl.find(start);
    }

    public String group() {
        return impl.group();
    }

    public String group(Integer index) {
        return impl.group(index);
    }

    public Integer groupCount() {
        return impl.groupCount();
    }

    public Integer start() {
        return impl.start();
    }

    public Integer end() {
        return impl.end();
    }

    public Boolean hitEnd() {
        return impl.hitEnd();
    }
}
