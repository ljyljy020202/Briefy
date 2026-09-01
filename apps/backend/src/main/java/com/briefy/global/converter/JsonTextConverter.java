package com.briefy.global.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;

/**
 * JPA {@link AttributeConverter} 기반 클래스. 엔티티 필드를 DB TEXT 컬럼의 JSON 문자열로 직렬화·역직렬화한다.
 *
 * <p>서브클래스는 생성자에서 {@link #targetType}을 지정하기만 하면 된다.
 *
 * <pre>{@code
 * @Converter
 * public class PostingTrackListConverter extends JsonTextConverter<List<PostingTrack>> {
 *   public PostingTrackListConverter() {
 *     super(MAPPER.getTypeFactory().constructCollectionType(List.class, PostingTrack.class));
 *   }
 * }
 * }</pre>
 */
public abstract class JsonTextConverter<T> implements AttributeConverter<T, String> {

  protected static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final JavaType targetType;

  protected JsonTextConverter(JavaType targetType) {
    this.targetType = targetType;
  }

  @Override
  public String convertToDatabaseColumn(T attribute) {
    if (attribute == null) return null;
    try {
      return MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON 직렬화 실패: " + e.getMessage(), e);
    }
  }

  @Override
  public T convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) return null;
    try {
      return MAPPER.readValue(dbData, targetType);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON 역직렬화 실패: " + e.getMessage(), e);
    }
  }
}
