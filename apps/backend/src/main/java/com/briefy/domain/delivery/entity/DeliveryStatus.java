package com.briefy.domain.delivery.entity;

public enum DeliveryStatus {
  PENDING,
  /** 이메일 전송 중 (선점 완료, EmailSender.send() 호출 전후). 프로세스 재시작 시 FAILED로 초기화됨. */
  SENDING,
  SENT,
  FAILED
}
