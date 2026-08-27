package com.briefy.domain.delivery.service;

import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.repository.DeliveryLogRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeliveryLog의 모든 DB 조작을 REQUIRES_NEW 트랜잭션으로 위임하는 서비스.
 *
 * <p>EmailDeliveryService의 이메일 발송 로직(TX 없음)과 상태 저장을 분리하기 위해 도입. 각 메서드는 독립된 짧은 트랜잭션으로 실행된다.
 */
@Service
public class DeliveryLogPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(DeliveryLogPersistenceService.class);

  private final DeliveryLogRepository deliveryLogRepository;

  public DeliveryLogPersistenceService(DeliveryLogRepository deliveryLogRepository) {
    this.deliveryLogRepository = deliveryLogRepository;
  }

  /**
   * 멱등적 DeliveryLog 생성. UNIQUE(briefing_report_id) 충돌 시 기존 로그 재조회.
   *
   * @return 새로 생성되거나 기존에 존재하는 DeliveryLog
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeliveryLog createOrGet(
      Long briefingReportId, Long userId, String toEmail, String subject) {
    try {
      DeliveryLog newLog = DeliveryLog.createPending(userId, briefingReportId, toEmail, subject);
      return deliveryLogRepository.save(newLog);
    } catch (DataIntegrityViolationException ex) {
      return deliveryLogRepository.findByBriefingReportId(briefingReportId).orElseThrow(() -> ex);
    }
  }

  /**
   * PENDING 또는 FAILED → SENDING 조건부 선점.
   *
   * @return true이면 이 호출이 선점 성공 (다른 동시 요청은 false 반환)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForSending(Long deliveryLogId) {
    int updated = deliveryLogRepository.claimForSending(deliveryLogId, LocalDateTime.now());
    return updated == 1;
  }

  /** SENDING → SENT 상태로 전환하고 providerMessageId를 저장. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markSent(Long deliveryLogId, String providerMessageId) {
    deliveryLogRepository.findById(deliveryLogId).ifPresent(dl -> dl.markSent(providerMessageId));
  }

  /** SENDING → FAILED 상태로 전환하고 errorMessage를 저장. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(Long deliveryLogId, String errorMessage) {
    deliveryLogRepository.findById(deliveryLogId).ifPresent(dl -> dl.markFailed(errorMessage));
  }

  /** retry_count를 1 증가시키고 last_attempt_at을 갱신. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void incrementRetry(Long deliveryLogId) {
    deliveryLogRepository.findById(deliveryLogId).ifPresent(DeliveryLog::incrementRetry);
  }

  /** briefingReportId로 DeliveryLog를 조회. */
  @Transactional(readOnly = true)
  public Optional<DeliveryLog> findByReportId(Long briefingReportId) {
    return deliveryLogRepository.findByBriefingReportId(briefingReportId);
  }
}
