package com.briefy.domain.briefingpreference.repository;

import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBriefingPreferenceRepository
    extends JpaRepository<UserBriefingPreference, Long> {

  List<UserBriefingPreference> findAllByUserIdAndActiveTrue(Long userId);

  Optional<UserBriefingPreference> findByUserIdAndCategoryId(Long userId, Long categoryId);

  void deleteAllByUserId(Long userId);
}
