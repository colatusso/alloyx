// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code System.Label} — custom-label text lookup. In Apex you read a label as a member:
 * {@code Label.My_Label} (or {@code System.Label.My_Label}) returns the label's translated TEXT.
 *
 * <p>DEGRADATION: the actual label text lives in org metadata, which isn't available locally. So
 * that code still RUNS (concatenations, comparisons, returns), each label resolves to its own
 * DEVELOPER NAME as the String value — a stable, non-null stand-in for the missing translation.
 * The transpiler can't emit {@code Label.My_Label} (Java has no such field), so it rewrites a
 * {@code Label.X} / {@code System.Label.X} member read to {@code Label.get("X")}.
 */
public final class Label {
    private Label() {
    }

    /**
     * The text of the custom label with the given developer name. Locally there's no org metadata,
     * so the developer name itself is returned as an honest, stable stand-in.
     */
    public static String get(String developerName) {
        return developerName;
    }
}
