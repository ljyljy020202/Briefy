package com.briefy.global.email;

/**
 * 이메일 발송 결과.
 *
 * @param success 발송 성공 여부
 * @param retryable 일시적 오류로 재시도 가능 여부 (false이면 영구 오류 — 재시도해도 무의미)
 * @param providerMessageId 성공 시 제공자가 반환한 메시지 ID
 * @param errorMessage 실패 시 오류 메시지
 */
public record EmailSendResult(
    boolean success, boolean retryable, String providerMessageId, String errorMessage) {

  /** 발송 성공 */
  public static EmailSendResult ok(String providerMessageId) {
    return new EmailSendResult(true, false, providerMessageId, null);
  }

  /** 영구 오류 — 재시도해도 성공 가능성 없음 (예: 잘못된 이메일 주소, SES 설정 오류) */
  public static EmailSendResult fail(String errorMessage) {
    return new EmailSendResult(false, false, null, errorMessage);
  }

  /** 일시적 오류 — 재시도 시 성공 가능 (예: 네트워크 오류, SES 스로틀링) */
  public static EmailSendResult transientFail(String errorMessage) {
    return new EmailSendResult(false, true, null, errorMessage);
  }
}
