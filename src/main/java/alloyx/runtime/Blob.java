// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.nio.charset.StandardCharsets;

/** Apex {@code Blob} — a binary buffer. Backed by a real byte[] so encoding works locally. */
public final class Blob {
    private final byte[] data;

    private Blob(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    /** Wrap raw bytes (package-internal: used by EncodingUtil/Http). */
    static Blob of(byte[] bytes) {
        return new Blob(bytes);
    }

    byte[] bytes() {
        return data;
    }

    public static Blob valueOf(String value) {
        return new Blob(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    public Integer size() {
        return data.length;
    }

    /** Apex {@code Blob.toString()} decodes the bytes as a UTF-8 string. */
    @Override
    public String toString() {
        return new String(data, StandardCharsets.UTF_8);
    }
}
