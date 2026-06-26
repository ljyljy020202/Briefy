package com.briefy.global.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResult<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <T> PageResult<T> from(Page<T> springPage) {
    return new PageResult<>(
        springPage.getContent(),
        springPage.getNumber(),
        springPage.getSize(),
        springPage.getTotalElements(),
        springPage.getTotalPages());
  }
}
