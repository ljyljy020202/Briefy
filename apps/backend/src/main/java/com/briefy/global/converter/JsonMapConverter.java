package com.briefy.global.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(Map<String, Object> attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize preference JSON", e);
    }
  }

  @Override
  public Map<String, Object> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(dbData, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize preference JSON", e);
    }
  }
}
