package com.briefy.domain.briefingpreference.repository;

import com.briefy.domain.briefingpreference.entity.BriefingCategory;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingCategoryRepository extends JpaRepository<BriefingCategory, Long> {

  List<BriefingCategory> findAllByActiveTrueOrderByIdAsc();

  Optional<BriefingCategory> findByIdAndActiveTrue(Long id);

  Optional<BriefingCategory> findByCode(BriefingCategoryCode code);
}
