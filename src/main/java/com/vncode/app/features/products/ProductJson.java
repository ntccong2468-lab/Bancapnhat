package com.vncode.app.features.products;

import com.google.gson.*;
import java.time.*;
public final class ProductJson {
    public static final Gson GSON=new GsonBuilder().enableComplexMapKeySerialization()
        .registerTypeAdapter(Instant.class,new TemporalAdapter<>(Instant::parse))
        .registerTypeAdapter(LocalDate.class,new TemporalAdapter<>(LocalDate::parse)).create();
    private ProductJson(){}
    private record TemporalAdapter<T>(java.util.function.Function<String,T> parser) implements JsonSerializer<T>,JsonDeserializer<T> {
        public JsonElement serialize(T value,java.lang.reflect.Type type,JsonSerializationContext context){return new JsonPrimitive(value.toString());}
        public T deserialize(JsonElement value,java.lang.reflect.Type type,JsonDeserializationContext context){return parser.apply(value.getAsString());}
    }
}
