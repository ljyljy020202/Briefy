package com.briefy.domain.delivery.service;

import com.briefy.domain.delivery.repository.DeliveryLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애플리케이션 재시작 시 SENDING 상태로 남은 DeliveryLog를 FAILED로 초기화.
 *
 * <h2>발생 원인</h2>
 *
 * <p>EmailSender.send()가 SES에 요청을 보낸 직후 DB SENT 갱신 전 프로세스가 비정상 종료되면 DeliveryLog가 SENDING 상태로 남는다. 이
 * 경우 재시작 후 재시도가 가능하도록 FAILED로 초기화한다.
 *
 * <h2>중복 발송 가능성</h2>
 *
 * <p>SES가 이미 이메일을 수락·발송했으나 DB 갱신 전 종료된 경우, FAILED 초기화 후 재시도하면 동일 이메일이 두 번 발송될 수 있다. exactly-once
 * 보장을 위한 분산 시스템(Outbox 테이블, 분산 락)은 현재 구현하지 않는다.
 */
@Component
public class DeliveryLogStartupResetter {

  private static final Logger log = LoggerFactory.getLogger(DeliveryLogStartupResetter.class);

  private final DeliveryLogRepository deliveryLogRepository;

  public DeliveryLogStartupResetter(DeliveryLogRepository deliveryLogRepository) {
    this.deliveryLogRepository = deliveryLogRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void resetStuckSendingLogs() {
    int count = deliveryLogRepository.resetSendingToFailed();
    if (count > 0) {
      log.warn(
          "Reset {} stuck SENDING delivery log(s) to FAILED on startup. "
              + "These logs may be retried — duplicate emails are possible if SES already accepted the request.",
          count);
    }
  }
}
