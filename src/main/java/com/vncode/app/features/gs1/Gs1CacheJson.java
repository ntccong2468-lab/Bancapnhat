package com.vncode.app.features.gs1;

import com.google.gson.*;
import java.time.*;

/** Only normalized source observations are cached; no session cookie, token or application XML. */
final class Gs1CacheJson {
    static final Gson JSON=new GsonBuilder()
        .registerTypeAdapter(Instant.class,(JsonSerializer<Instant>)(value,type,context)->new JsonPrimitive(value.toString()))
        .registerTypeAdapter(Instant.class,(JsonDeserializer<Instant>)(value,type,context)->Instant.parse(value.getAsString()))
        .registerTypeAdapter(LocalDate.class,(JsonSerializer<LocalDate>)(value,type,context)->new JsonPrimitive(value.toString()))
        .registerTypeAdapter(LocalDate.class,(JsonDeserializer<LocalDate>)(value,type,context)->LocalDate.parse(value.getAsString())).create();
}
