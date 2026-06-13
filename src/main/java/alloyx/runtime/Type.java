// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * The Apex {@code System.Type} — runtime reflection. {@code Type.forName(name)}
 * resolves a type by API name and {@code newInstance()} constructs it. Locally we
 * support the cases transpiled code actually uses: building an sObject, a List, a
 * Set or a Map by name (e.g. a generic SObjectFactory).
 */
public final class Type {
    private final String name;

    private Type(String name) {
        this.name = name;
    }

    public static Type forName(String name) {
        return new Type(name);
    }

    /** Constructs an instance of the named type (empty collection, or a new sObject). */
    public Object newInstance() {
        String n = name == null ? "" : name.trim();
        if (n.startsWith("List<") || n.equals("List")) {
            return new List<>();
        }
        if (n.startsWith("Set<") || n.equals("Set")) {
            return new Set<>();
        }
        if (n.startsWith("Map<") || n.equals("Map")) {
            return new Map<>();
        }
        return new SObject(n); // an sObject API name -> a dynamic record of that type
    }

    public String getName() {
        return name;
    }

    // Apex compares type tokens by the type they denote: `Integer.class == Integer.class` is true,
    // and a `List<Type>` equality (e.g. fflib_QualifiedMethod) compares the named types element-wise.
    // Identity would break both, so equal by (case-insensitive) name — the API name IS the identity.
    @Override
    public boolean equals(Object other) {
        return other instanceof Type t
            && (name == null ? t.name == null : name.equalsIgnoreCase(t.name));
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.toLowerCase(java.util.Locale.ROOT).hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
