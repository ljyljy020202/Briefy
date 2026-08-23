package com.briefy.global.email;

public record EmailSendResult(boolean success, String providerMessageId, String errorMessage) {

  public static EmailSendResult ok(String providerMessageId) {
    return new EmailSendResult(true, providerMessageId, null);
  }

  public static EmailSendResult fail(String errorMessage) {
    return new EmailSendResult(false, null, errorMessage);
  }
}
