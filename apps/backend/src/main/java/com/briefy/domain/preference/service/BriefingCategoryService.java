package com.briefy.domain.preference.service;

import com.briefy.domain.preference.dto.BriefingCategoryResponse;
import com.briefy.domain.preference.repository.BriefingCategoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BriefingCategoryService {

  private final BriefingCategoryRepository briefingCategoryRepository;

  public BriefingCategoryService(BriefingCategoryRepository briefingCategoryRepository) {
    this.briefingCategoryRepository = briefingCategoryRepository;
  }

  public List<BriefingCategoryResponse> getActiveCategories() {
    return briefingCategoryRepository.findAllByActiveTrueOrderByIdAsc().stream()
        .map(BriefingCategoryResponse::from)
        .toList();
  }
}
