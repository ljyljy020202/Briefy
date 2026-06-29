package com.briefy.domain.collection.repository;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

  List<CollectionJob> findAllByCollectionDate(LocalDate collectionDate);

  List<CollectionJob> findAllByStatus(CollectionJobStatus status);

  Optional<CollectionJob> findTopByCollectionDateAndStatusOrderByCreatedAtDesc(
      LocalDate collectionDate, CollectionJobStatus status);
}
