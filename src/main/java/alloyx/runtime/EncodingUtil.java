package alloyx.runtime;

/** Apex {@code EncodingUtil} — recognized for type-checking; not executed locally yet. */
public final class EncodingUtil {
    public static Blob base64Decode(String value) {
        throw Unsupported.notLocal("EncodingUtil.base64Decode");
    }

    public static String base64Encode(Blob value) {
        throw Unsupported.notLocal("EncodingUtil.base64Encode");
    }

    public static Blob convertFromHex(String input) {
        throw Unsupported.notLocal("EncodingUtil.convertFromHex");
    }

    public static String convertToHex(Blob value) {
        throw Unsupported.notLocal("EncodingUtil.convertToHex");
    }

    public static String urlDecode(String value, String encoding) {
        throw Unsupported.notLocal("EncodingUtil.urlDecode");
    }

    public static String urlEncode(String value, String encoding) {
        throw Unsupported.notLocal("EncodingUtil.urlEncode");
    }
}
