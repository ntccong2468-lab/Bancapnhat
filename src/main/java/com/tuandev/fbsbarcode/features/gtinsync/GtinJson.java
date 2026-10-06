package com.tuandev.fbsbarcode.features.gtinsync;

import com.google.gson.*;
import java.time.Instant;

final class GtinJson {
    private GtinJson() {}
    static final Gson GSON = new GsonBuilder().registerTypeAdapter(Instant.class,
            new InstantAdapter()).create();
    private static final class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        public JsonElement serialize(Instant value, java.lang.reflect.Type type, JsonSerializationContext context) {
            return new JsonPrimitive(value.toString());
        }
        public Instant deserialize(JsonElement value, java.lang.reflect.Type type, JsonDeserializationContext context) {
            return Instant.parse(value.getAsString());
        }
    }
}
