package com.briefy.domain.delivery.repository;

import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {

  boolean existsByBriefingReportIdAndStatus(Long briefingReportId, DeliveryStatus status);

  Optional<DeliveryLog> findByBriefingReportId(Long briefingReportId);
}
