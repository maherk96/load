package org.example.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JsonUtil {

  private static final ObjectMapper MAPPER;

  static {
    MAPPER =
        JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature())
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    MAPPER.registerModule(new JavaTimeModule());
  }

  private JsonUtil() {
    // Prevent instantiation
  }

  /** Reads JSON from string into the given class type. */
  public static <T> T read(String json, Class<T> type) throws JsonProcessingException {
    return MAPPER.readValue(json, type);
  }

  /** Converts an object to a JSON string (pretty printed). */
  public static String toJson(Object obj) throws JsonProcessingException {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
  }

  /** Returns the shared ObjectMapper instance. */
  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
