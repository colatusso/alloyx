package alloyx.runtime;

/**
 * Apex {@code Schema.SObjectType} — identifies an sObject type and exposes its
 * describe. Returned by {@link SObject#getSObjectType()} (Apex returns this, not a
 * String). It stringifies to the API name, so existing String concatenations
 * (e.g. REST URLs in the gateway) keep working unchanged.
 */
public final class SObjectType {
    private final String name;

    public SObjectType(String name) {
        this.name = name;
    }

    public DescribeSObjectResult getDescribe() {
        return new DescribeSObjectResult(name);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SObjectType other && java.util.Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }
}
