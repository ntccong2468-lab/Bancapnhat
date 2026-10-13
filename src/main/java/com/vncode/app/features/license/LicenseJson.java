package com.vncode.app.features.license;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.HashSet;

/** Bounded JSON parsing that rejects duplicate fields rather than silently replacing them. */
public final class LicenseJson {
    private LicenseJson() {}
    public static JsonObject object(String text) throws IOException {
        if (text == null || text.length() > 65536) throw invalid();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return value.getAsJsonObject();
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    private static JsonElement read(JsonReader r, int depth) throws IOException {
        if (depth > 12) throw invalid();
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                var result = new JsonObject(); var keys = new HashSet<String>(); r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (!keys.add(key) || keys.size() > 64 || key.length() > 128) throw invalid();
                    result.add(key, read(r, depth + 1));
                }
                r.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                var result = new JsonArray(); r.beginArray();
                while (r.hasNext()) { if (result.size() >= 100) throw invalid(); result.add(read(r, depth + 1)); }
                r.endArray(); yield result;
            }
            case STRING -> { String s = r.nextString(); if (s.length() > 50000) throw invalid(); yield new JsonPrimitive(s); }
            case NUMBER -> {
                String s = r.nextString();
                if (!s.matches("-?(0|[1-9][0-9]{0,18})")) throw invalid();
                yield new JsonPrimitive(Long.parseLong(s));
            }
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid();
        };
    }
    public static String string(JsonObject o, String name, int max) throws IOException {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid();
        String value = e.getAsString(); if (value.length() > max) throw invalid(); return value;
    }
    public static long number(JsonObject o, String name) throws IOException {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw invalid();
        return e.getAsLong();
    }
    public static IOException invalid() { return new IOException("LICENSE_PROTOCOL_INVALID"); }
}
