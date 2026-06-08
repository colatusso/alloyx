package alloyx.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

/**
 * Apex {@code System.JSON} runtime type.
 *
 * <p>Backed by Gson. The serialization side registers a custom serializer for
 * {@link SObject} so a dynamic record is emitted as a plain JSON object of its
 * fields (e.g. {@code {"Name":"Acme","Qty":5}}), never the wrapper or its
 * SObject type — matching Apex {@code JSON.serialize(anSObject)}. Lists of
 * SObjects therefore become a JSON array of those field objects.
 *
 * <p>The deserialization side walks Gson's {@link JsonElement} tree by hand so
 * that, like Apex, objects come back as {@code Map<String,Object>}, arrays as
 * {@code List<Object>}, and numbers keep an integral/fractional distinction
 * ({@code Long} vs {@link Decimal}) instead of all collapsing to {@code Double}.
 */
public final class JSON {

    private JSON() {
    }

    /**
     * Emit an {@link SObject} as a bare JSON object of its fields. Each field
     * value is delegated back to Gson so nested SObjects, lists, maps, Dates,
     * Decimals, etc. all serialize through the same configured context.
     */
    private static final JsonSerializer<SObject> SOBJECT_SERIALIZER =
        (SObject src, Type typeOfSrc, JsonSerializationContext context) -> {
            if (src == null) {
                return JsonNull.INSTANCE;
            }
            JsonObject obj = new JsonObject();
            for (java.util.Map.Entry<String, Object> e : src.getFields().entrySet()) {
                obj.add(e.getKey(), context.serialize(e.getValue()));
            }
            return obj;
        };

    /** Shared, immutable-config Gson used by both serialize methods. */
    private static final Gson GSON = new GsonBuilder()
        .registerTypeHierarchyAdapter(SObject.class, SOBJECT_SERIALIZER)
        .create();

    /** Same configuration, with indentation, for {@link #serializePretty(Object)}. */
    private static final Gson GSON_PRETTY = new GsonBuilder()
        .registerTypeHierarchyAdapter(SObject.class, SOBJECT_SERIALIZER)
        .setPrettyPrinting()
        .create();

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    /** Apex {@code JSON.serialize(Object)}: compact JSON string. */
    public static String serialize(Object o) {
        return GSON.toJson(o);
    }

    /** Apex {@code JSON.serializePretty(Object)}: pretty-printed JSON string. */
    public static String serializePretty(Object o) {
        return GSON_PRETTY.toJson(o);
    }

    // ------------------------------------------------------------------
    // Deserialization
    // ------------------------------------------------------------------

    /**
     * Apex {@code JSON.deserializeUntyped(String)}: parse into the generic Apex
     * shape — {@code Map<String,Object>} for objects, {@code List<Object>} for
     * arrays, and {@code String}/{@code Boolean}/{@code Long}/{@link Decimal}
     * for scalars. Returns {@code null} for JSON {@code null} (or null input).
     */
    public static Object deserializeUntyped(String json) {
        if (json == null) {
            return null;
        }
        return toApexValue(JsonParser.parseString(json));
    }

    /**
     * Apex {@code JSON.deserialize(String, System.Type)}: typed deserialization.
     * Typed binding needs reflection we skip in this version, so this is a
     * best-effort delegation to {@link #deserializeUntyped(String)}; the
     * {@code type} argument is accepted and ignored.
     */
    public static Object deserialize(String json, Object type) {
        return deserializeUntyped(json);
    }

    /** Recursively convert a Gson element into the Apex untyped value model. */
    private static Object toApexValue(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            Map<String, Object> map = new Map<>();
            for (java.util.Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), toApexValue(e.getValue()));
            }
            return map;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            List<Object> list = new List<>();
            for (JsonElement child : arr) {
                list.add(toApexValue(child));
            }
            return list;
        }
        return toApexScalar(el.getAsJsonPrimitive());
    }

    /**
     * Map a JSON primitive to an Apex scalar. Strings and booleans pass through;
     * numbers are inspected textually so integral values become {@code Long}
     * (and fall back to {@link Decimal} only if they overflow a long) while
     * anything with a fraction or exponent becomes a {@link Decimal}.
     */
    private static Object toApexScalar(JsonPrimitive prim) {
        if (prim.isBoolean()) {
            return prim.getAsBoolean();
        }
        if (prim.isString()) {
            return prim.getAsString();
        }
        // Number: decide integral vs fractional from the literal text.
        String text = prim.getAsString();
        boolean integral = text.indexOf('.') < 0
            && text.indexOf('e') < 0
            && text.indexOf('E') < 0;
        if (integral) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException overflow) {
                // Too big for a long: keep full precision as a Decimal.
                return new Decimal(text);
            }
        }
        return new Decimal(text);
    }
}
