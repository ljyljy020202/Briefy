package com.briefy.domain.collection.repository;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

  List<CollectionJob> findAllByCollectionDate(LocalDate collectionDate);

  List<CollectionJob> findAllByStatus(CollectionJobStatus status);

  Optional<CollectionJob> findTopByCollectionDateAndStatusOrderByCreatedAtDesc(
      LocalDate collectionDate, CollectionJobStatus status);

  boolean existsByCollectionDateAndStatusIn(
      LocalDate collectionDate, List<CollectionJobStatus> statuses);

  Optional<CollectionJob> findByCollectionDate(LocalDate collectionDate);

  @Modifying
  @Query(
      """
      UPDATE CollectionJob j
      SET j.status = com.briefy.domain.collection.entity.CollectionJobStatus.PROCESSING,
          j.startedAt = :now
      WHERE j.id = :id
      AND j.status = com.briefy.domain.collection.entity.CollectionJobStatus.PENDING
      """)
  int claimPending(@Param("id") Long id, @Param("now") LocalDateTime now);

  // FAILED → PROCESSING 조건부 선점 (retry용)
  @Modifying
  @Query(
      """
      UPDATE CollectionJob j
      SET j.status = com.briefy.domain.collection.entity.CollectionJobStatus.PROCESSING,
          j.startedAt = :now,
          j.completedAt = null,
          j.errorMessage = null,
          j.retryCount = j.retryCount + 1
      WHERE j.id = :id
      AND j.status = com.briefy.domain.collection.entity.CollectionJobStatus.FAILED
      AND j.retryCount < :maxRetries
      """)
  int claimFailedForRetry(
      @Param("id") Long id,
      @Param("now") java.time.LocalDateTime now,
      @Param("maxRetries") int maxRetries);
}
