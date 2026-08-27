package com.briefy.domain.delivery.repository;

import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {

  boolean existsByBriefingReportIdAndStatus(Long briefingReportId, DeliveryStatus status);

  Optional<DeliveryLog> findByBriefingReportId(Long briefingReportId);

  /**
   * PENDING 또는 FAILED → SENDING 조건부 선점. 동시 요청 중 하나만 성공(rowCount=1).
   *
   * @return 갱신된 행 수 (1이면 선점 성공, 0이면 이미 다른 요청이 선점함)
   */
  @Modifying
  @Query(
      """
      UPDATE DeliveryLog d
      SET d.status = com.briefy.domain.delivery.entity.DeliveryStatus.SENDING,
          d.lastAttemptAt = :now
      WHERE d.id = :id
      AND d.status IN (
          com.briefy.domain.delivery.entity.DeliveryStatus.PENDING,
          com.briefy.domain.delivery.entity.DeliveryStatus.FAILED
      )
      """)
  int claimForSending(@Param("id") Long id, @Param("now") LocalDateTime now);

  /**
   * 애플리케이션 재시작 시 SENDING 상태로 남은 행을 FAILED로 초기화.
   *
   * @return 초기화된 행 수
   */
  @Modifying
  @Query(
      """
      UPDATE DeliveryLog d
      SET d.status = com.briefy.domain.delivery.entity.DeliveryStatus.FAILED,
          d.errorMessage = 'process_restart_recovery'
      WHERE d.status = com.briefy.domain.delivery.entity.DeliveryStatus.SENDING
      """)
  int resetSendingToFailed();
}
