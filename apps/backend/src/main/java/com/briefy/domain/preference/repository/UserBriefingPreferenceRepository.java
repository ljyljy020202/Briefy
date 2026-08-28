package com.briefy.domain.preference.repository;

import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBriefingPreferenceRepository
    extends JpaRepository<UserBriefingPreference, Long> {

  @Query(
      "SELECT p FROM UserBriefingPreference p JOIN FETCH p.category"
          + " WHERE p.userId = :userId AND p.active = true")
  List<UserBriefingPreference> findAllByUserIdAndActiveTrue(@Param("userId") Long userId);

  List<UserBriefingPreference> findAllByCategoryCodeAndActiveTrue(BriefingCategoryCode code);

  Optional<UserBriefingPreference> findByUserIdAndCategoryId(Long userId, Long categoryId);

  void deleteAllByUserId(Long userId);
}
