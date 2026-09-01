package com.briefy.domain.candidatepool.entity.analysis;

import com.briefy.global.converter.JsonTextConverter;
import jakarta.persistence.Converter;
import java.util.List;

/** {@link PostingTrack} 리스트를 DB TEXT 컬럼의 JSON 배열로 직렬화·역직렬화한다. */
@Converter
public class PostingTrackListConverter extends JsonTextConverter<List<PostingTrack>> {

  public PostingTrackListConverter() {
    super(MAPPER.getTypeFactory().constructCollectionType(List.class, PostingTrack.class));
  }
}
