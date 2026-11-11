package com.jdmmc.kurumamod.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.io.Reader;

/**
 * JSON を読む道具。<b>バニラの {@code net.minecraft.util.GsonHelper} と同じ名前・同じ動き</b>（core は Minecraft を
 * 知らないので写した）。カーパックの JSON を読むところ（車種・部品・見た目の定義）が使う。
 *
 * <p>無い鍵・型の違う値は、既定値のある形ならその値、無い形なら {@link JsonSyntaxException}（バニラと同じ）。</p>
 */
public final class GsonHelper {

    private GsonHelper() {
    }

    public static boolean isStringValue(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isString();
    }

    public static boolean isNumberValue(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber();
    }

    public static boolean isBooleanValue(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean();
    }

    public static boolean isArrayNode(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray();
    }

    public static boolean isObjectNode(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject();
    }

    private static String type(JsonElement element) {
        String text = String.valueOf(element);
        if (text.length() > 10) {
            text = text.substring(0, 10) + "...";
        }
        if (element == null) {
            return "null (missing)";
        }
        if (element.isJsonNull()) {
            return "NULL";
        }
        if (element.isJsonArray()) {
            return "an array (" + text + ")";
        }
        if (element.isJsonObject()) {
            return "an object (" + text + ")";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return "a number (" + text + ")";
        }
        if (primitive.isBoolean()) {
            return "a boolean (" + text + ")";
        }
        return text;
    }

    private static JsonElement require(JsonObject json, String key, String expected) {
        if (!json.has(key)) {
            throw new JsonSyntaxException("Missing " + key + ", expected to find " + expected);
        }
        return json.get(key);
    }

    public static String convertToString(JsonElement element, String name) {
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a string, was " + type(element));
    }

    public static String getAsString(JsonObject json, String key) {
        return convertToString(require(json, key, "a string"), key);
    }

    public static String getAsString(JsonObject json, String key, String fallback) {
        return json.has(key) ? convertToString(json.get(key), key) : fallback;
    }

    public static boolean convertToBoolean(JsonElement element, String name) {
        if (element.isJsonPrimitive()) {
            return element.getAsBoolean();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a Boolean, was " + type(element));
    }

    public static boolean getAsBoolean(JsonObject json, String key) {
        return convertToBoolean(require(json, key, "a Boolean"), key);
    }

    public static boolean getAsBoolean(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? convertToBoolean(json.get(key), key) : fallback;
    }

    public static double convertToDouble(JsonElement element, String name) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a Double, was " + type(element));
    }

    public static double getAsDouble(JsonObject json, String key) {
        return convertToDouble(require(json, key, "a Double"), key);
    }

    public static double getAsDouble(JsonObject json, String key, double fallback) {
        return json.has(key) ? convertToDouble(json.get(key), key) : fallback;
    }

    public static float convertToFloat(JsonElement element, String name) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsFloat();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a Float, was " + type(element));
    }

    public static float getAsFloat(JsonObject json, String key) {
        return convertToFloat(require(json, key, "a Float"), key);
    }

    public static float getAsFloat(JsonObject json, String key, float fallback) {
        return json.has(key) ? convertToFloat(json.get(key), key) : fallback;
    }

    public static int convertToInt(JsonElement element, String name) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a Int, was " + type(element));
    }

    public static int getAsInt(JsonObject json, String key) {
        return convertToInt(require(json, key, "a Int"), key);
    }

    public static int getAsInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? convertToInt(json.get(key), key) : fallback;
    }

    public static JsonObject convertToJsonObject(JsonElement element, String name) {
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a JsonObject, was " + type(element));
    }

    public static JsonObject getAsJsonObject(JsonObject json, String key) {
        return convertToJsonObject(require(json, key, "a JsonObject"), key);
    }

    public static JsonObject getAsJsonObject(JsonObject json, String key, JsonObject fallback) {
        return json.has(key) ? convertToJsonObject(json.get(key), key) : fallback;
    }

    public static JsonArray convertToJsonArray(JsonElement element, String name) {
        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        throw new JsonSyntaxException("Expected " + name + " to be a JsonArray, was " + type(element));
    }

    public static JsonArray getAsJsonArray(JsonObject json, String key) {
        return convertToJsonArray(require(json, key, "a JsonArray"), key);
    }

    public static JsonArray getAsJsonArray(JsonObject json, String key, JsonArray fallback) {
        return json.has(key) ? convertToJsonArray(json.get(key), key) : fallback;
    }

    /** JSON のオブジェクトとして読む（バニラと同じく寛容な読み方）。 */
    public static JsonObject parse(Reader reader) {
        return convertToJsonObject(JsonParser.parseReader(reader), "json");
    }

    public static JsonObject parse(String text) {
        return convertToJsonObject(JsonParser.parseString(text), "json");
    }
}
