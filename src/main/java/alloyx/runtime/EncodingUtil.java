// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HexFormat;

/** Apex {@code EncodingUtil} — base64 / hex / URL encoding, run for real via the JDK. */
public final class EncodingUtil {
    private EncodingUtil() {}

    public static Blob base64Decode(String value) {
        return Blob.of(Base64.getDecoder().decode(value));
    }

    public static String base64Encode(Blob value) {
        return Base64.getEncoder().encodeToString(value.bytes());
    }

    public static Blob convertFromHex(String input) {
        return Blob.of(HexFormat.of().parseHex(input));
    }

    public static String convertToHex(Blob value) {
        return HexFormat.of().formatHex(value.bytes());
    }

    public static String urlDecode(String value, String encoding) {
        return URLDecoder.decode(value, Charset.forName(encoding));
    }

    public static String urlEncode(String value, String encoding) {
        return URLEncoder.encode(value, Charset.forName(encoding));
    }
}
