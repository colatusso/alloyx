// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import com.google.gson.Gson;

/**
 * Apex {@code System.JSONGenerator} — a streaming JSON writer obtained from
 * {@link JSON#createGenerator(Boolean)}.
 *
 * <p>Pure string-building with no org dependency, so it runs LOCALLY: a probe drives the writer
 * and {@link #getAsString()} returns the JSON produced. Separators (the comma between siblings)
 * and the field-name/value pairing are tracked on a small container stack, so well-formed JSON
 * comes out for the object/array nesting real code builds. Pretty-printing adds newline +
 * indentation; the compact mode emits no whitespace, matching the platform.
 */
public final class JSONGenerator {
    private final StringBuilder sb = new StringBuilder();
    private final boolean pretty;
    // nesting depth and, per level, whether at least one entry was already written (so the next
    // needs a leading comma). Index 0 is the document root.
    private final java.util.ArrayDeque<Boolean> hasEntry = new java.util.ArrayDeque<>();
    // Gson value encoder reused for arbitrary objects (writeObject), so nested SObjects/maps/lists
    // serialize through the same configured adapter as JSON.serialize.
    private static final Gson GSON = new Gson();

    JSONGenerator(boolean pretty) {
        this.pretty = pretty;
        hasEntry.push(Boolean.FALSE); // root level
    }

    // --- structure -------------------------------------------------------------------------------

    public void writeStartObject() {
        valuePrefix();
        sb.append('{');
        hasEntry.push(Boolean.FALSE);
    }

    public void writeEndObject() {
        closeContainer('}');
    }

    public void writeStartArray() {
        valuePrefix();
        sb.append('[');
        hasEntry.push(Boolean.FALSE);
    }

    public void writeEndArray() {
        closeContainer(']');
    }

    /** A field name inside an object; the next {@code write*} supplies its value. */
    public void writeFieldName(String name) {
        separator();
        newlineIndent(depth());
        sb.append('"').append(escape(name)).append('"').append(pretty ? " : " : ":");
        pendingFieldValue = true; // the value that follows must NOT re-emit a separator/indent
    }

    // --- scalar values ---------------------------------------------------------------------------

    public void writeString(String value) {
        valuePrefix();
        sb.append(value == null ? "null" : "\"" + escape(value) + "\"");
    }

    public void writeNumber(Object value) {
        valuePrefix();
        sb.append(value == null ? "null" : value.toString());
    }

    public void writeBoolean(Boolean value) {
        valuePrefix();
        sb.append(value == null ? "null" : value.toString());
    }

    public void writeNull() {
        valuePrefix();
        sb.append("null");
    }

    public void writeDate(Date value) {
        writeString(value == null ? null : value.toString());
    }

    public void writeDateTime(Datetime value) {
        writeString(value == null ? null : value.toString());
    }

    public void writeId(String value) {
        writeString(value);
    }

    /** Serialize an arbitrary object (SObject/Map/List/POJO) into the stream via Gson. */
    public void writeObject(Object value) {
        valuePrefix();
        sb.append(JSON.serialize(value));
    }

    // --- "<name>Field" convenience pairs (name + value in one call) ------------------------------

    public void writeStringField(String fieldName, String value) {
        writeFieldName(fieldName);
        writeString(value);
    }

    public void writeNumberField(String fieldName, Object value) {
        writeFieldName(fieldName);
        writeNumber(value);
    }

    public void writeBooleanField(String fieldName, Boolean value) {
        writeFieldName(fieldName);
        writeBoolean(value);
    }

    public void writeNullField(String fieldName) {
        writeFieldName(fieldName);
        writeNull();
    }

    public void writeDateField(String fieldName, Date value) {
        writeFieldName(fieldName);
        writeDate(value);
    }

    public void writeDateTimeField(String fieldName, Datetime value) {
        writeFieldName(fieldName);
        writeDateTime(value);
    }

    public void writeIdField(String fieldName, String value) {
        writeFieldName(fieldName);
        writeId(value);
    }

    public void writeObjectField(String fieldName, Object value) {
        writeFieldName(fieldName);
        writeObject(value);
    }

    /** The JSON written so far. */
    public String getAsString() {
        return sb.toString();
    }

    // --- internals -------------------------------------------------------------------------------

    // true between a writeFieldName and its value: the value is the field's, so it skips the
    // separator/indent (the field name already emitted them) but still marks the level as filled.
    private boolean pendingFieldValue = false;

    // Emit the separator + indentation that precedes a VALUE in array/root position; a value that
    // follows a field name was already positioned by writeFieldName, so it only clears the flag.
    private void valuePrefix() {
        if (pendingFieldValue) {
            pendingFieldValue = false;
            markEntry();
            return;
        }
        separator();
        newlineIndent(depth());
        markEntry();
    }

    // A comma before any sibling after the first at the current level.
    private void separator() {
        if (Boolean.TRUE.equals(hasEntry.peek())) {
            sb.append(',');
        }
    }

    // Record that the current level now holds at least one entry (so the next gets a comma).
    private void markEntry() {
        hasEntry.pop();
        hasEntry.push(Boolean.TRUE);
    }

    private void closeContainer(char close) {
        boolean wrote = Boolean.TRUE.equals(hasEntry.pop());
        if (wrote) {
            newlineIndent(depth());
        }
        sb.append(close);
    }

    private int depth() {
        return hasEntry.size() - 1; // root is depth 0
    }

    private void newlineIndent(int depth) {
        if (pretty && sb.length() > 0) {
            sb.append('\n');
            sb.append("  ".repeat(Math.max(0, depth)));
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
